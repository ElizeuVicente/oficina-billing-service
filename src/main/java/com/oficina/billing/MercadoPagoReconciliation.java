package com.oficina.billing;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;

@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "billing.mercadopago.reconciliation.enabled", havingValue = "true")
class MercadoPagoReconciliationConfiguration { }

/** Reads provider state; unknown or mismatching payments remain pending for investigation. */
@Component
@ConditionalOnProperty(name = "billing.mercadopago.reconciliation.enabled", havingValue = "true")
class MercadoPagoReconciliation {
    private static final Logger log = LoggerFactory.getLogger(MercadoPagoReconciliation.class);
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final RestClient provider;
    private final String collectorId;
    private final boolean expectedLiveMode;

    @org.springframework.beans.factory.annotation.Autowired
    MercadoPagoReconciliation(JdbcTemplate jdbc, TransactionTemplate transactions,
            @Value("${billing.mercadopago.access-token}") String accessToken,
            @Value("${billing.mercadopago.collector-id}") String collectorId,
            @Value("${billing.mercadopago.live-mode:false}") boolean expectedLiveMode) {
        this(jdbc, transactions, providerClient(accessToken), collectorId, expectedLiveMode);
    }
    MercadoPagoReconciliation(JdbcTemplate jdbc, TransactionTemplate transactions, RestClient provider, String collectorId, boolean expectedLiveMode) {
        if (collectorId == null || !collectorId.matches("[0-9]{1,20}")) throw new IllegalArgumentException("Invalid collector ID");
        this.jdbc = jdbc; this.transactions = transactions; this.provider = provider; this.collectorId = collectorId; this.expectedLiveMode = expectedLiveMode;
    }
    private static RestClient providerClient(String token) {
        if (token == null || token.isBlank()) throw new IllegalArgumentException("Mercado Pago access token is required");
        var http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        var factory = new JdkClientHttpRequestFactory(http); factory.setReadTimeout(Duration.ofSeconds(10));
        return RestClient.builder().baseUrl("https://api.mercadopago.com").requestFactory(factory)
                .defaultHeaders(headers -> headers.setBearerAuth(token)).build();
    }

    @Scheduled(fixedDelayString = "${billing.mercadopago.reconciliation.delay-ms:30000}")
    public void processPending() {
        var ids = jdbc.query("""
                SELECT request_id FROM mercadopago_notifications WHERE processed_at IS NULL
                ORDER BY last_attempt_at NULLS FIRST, received_at LIMIT 20
                """, (rs, row) -> rs.getString(1));
        for (String id : ids) {
            try { transactions.executeWithoutResult(status -> processOne(id)); }
            catch (RuntimeException failure) {
                // Never log provider payloads or credentials; failed notifications remain pending.
                log.warn("Mercado Pago notification reconciliation failed: {}", failure.getClass().getSimpleName());
                jdbc.update("UPDATE mercadopago_notifications SET attempts=attempts+1,last_attempt_at=CURRENT_TIMESTAMP,last_error=? WHERE request_id=? AND processed_at IS NULL",
                        failure.getClass().getSimpleName(), id);
            }
        }
    }

    private void processOne(String requestId) {
        var ids = jdbc.query("SELECT resource_id FROM mercadopago_notifications WHERE request_id=? AND processed_at IS NULL FOR UPDATE SKIP LOCKED",
                (rs, row) -> rs.getString(1), requestId);
        if (ids.isEmpty()) return;
        String resource = ids.get(0);
        if (!resource.matches("[0-9]{1,20}")) throw new IllegalStateException("Invalid payment ID");
        JsonNode payment = provider.get().uri("/v1/payments/{id}", resource).retrieve().body(JsonNode.class);
        if (payment == null || !resource.equals(payment.path("id").asText())
                || !collectorId.equals(payment.path("collector_id").asText()) || !"BRL".equals(payment.path("currency_id").asText())
                || !payment.path("live_mode").isBoolean() || payment.path("live_mode").booleanValue() != expectedLiveMode)
            throw new IllegalStateException("Provider payment identity mismatch");
        UUID attempt;
        BigDecimal amount;
        try { attempt = UUID.fromString(payment.path("external_reference").asText()); amount = new BigDecimal(payment.path("transaction_amount").asText()); }
        catch (IllegalArgumentException invalid) { throw new IllegalStateException("Invalid provider payment fields", invalid); }
        var rows = jdbc.query("""
                SELECT p.amount,p.status,p.provider_payment_id,q.status,q.id,q.order_id,q.branch_id,q.saga_id,q.correlation_id
                FROM payment_attempts p JOIN quotes q ON q.id=p.quote_id
                WHERE p.attempt_id=? FOR UPDATE OF p,q
                """, (rs, row) -> new Attempt(rs.getBigDecimal(1), rs.getString(2), rs.getString(3), rs.getString(4),
                rs.getObject(5, UUID.class), rs.getObject(6, UUID.class), rs.getObject(7, UUID.class), rs.getObject(8, UUID.class), rs.getObject(9, UUID.class)), attempt);
        if (rows.size() != 1 || rows.get(0).amount().compareTo(amount) != 0
                || !"APPROVED".equals(rows.get(0).quoteStatus())
                || (rows.get(0).providerId() != null && !rows.get(0).providerId().equals(resource)))
            throw new IllegalStateException("Payment does not match local attempt");
        String incoming = switch (payment.path("status").asText()) {
            case "approved" -> "APPROVED";
            case "rejected", "cancelled" -> "REJECTED";
            case "pending", "in_process" -> "PENDING";
            case "refunded" -> "REFUNDED";
            case "charged_back" -> "CHARGED_BACK";
            default -> throw new IllegalStateException("Unsupported provider status");
        };
        if (incoming.equals("APPROVED") && payment.hasNonNull("transaction_amount_refunded")) {
            try {
                if (new BigDecimal(payment.path("transaction_amount_refunded").asText()).signum() != 0)
                    throw new IllegalStateException("Partial refund requires manual reconciliation");
            } catch (NumberFormatException invalid) {
                throw new IllegalStateException("Invalid refund amount", invalid);
            }
        }
        String previous = rows.get(0).status();
        if (previous.equals("PENDING") && (incoming.equals("REFUNDED") || incoming.equals("CHARGED_BACK")))
            throw new IllegalStateException("Reversal before payment confirmation requires manual reconciliation");
        if (!previous.equals(incoming) && !previous.equals("PENDING")
                && !(previous.equals("APPROVED") && (incoming.equals("REFUNDED") || incoming.equals("CHARGED_BACK"))))
            throw new IllegalStateException("Provider payment status requires manual reconciliation");
        jdbc.update("UPDATE payment_attempts SET status=?,provider_payment_id=? WHERE attempt_id=?", incoming, resource, attempt);
        if (incoming.equals("REFUNDED")) jdbc.update("UPDATE refund_requests SET status='CONFIRMED',updated_at=CURRENT_TIMESTAMP WHERE attempt_id=?", attempt);
        if (!previous.equals(incoming) && !incoming.equals("PENDING")) recordPaymentEvent(rows.get(0), attempt, resource, incoming);
        jdbc.update("UPDATE mercadopago_notifications SET processed_at=CURRENT_TIMESTAMP,attempts=attempts+1,last_attempt_at=CURRENT_TIMESTAMP,last_error=NULL WHERE request_id=?", requestId);
    }
    private void recordPaymentEvent(Attempt data, UUID attemptId, String providerId, String status) {
        UUID eventId = UUID.randomUUID();
        var now = java.time.Instant.now();
        String type = switch (status) {
            case "APPROVED" -> "PagamentoConfirmado";
            case "REJECTED" -> "PagamentoRecusado";
            case "REFUNDED" -> "PagamentoEstornado";
            case "CHARGED_BACK" -> "PagamentoContestado";
            default -> throw new IllegalArgumentException("Unsupported payment event");
        };
        var envelope = java.util.Map.of("eventId", eventId, "eventType", type, "schemaVersion", 1,
                "occurredAt", now.toString(), "sagaId", data.sagaId(), "osId", data.orderId(),
                "filialId", data.branchId(), "correlationId", data.correlationId(),
                "payload", java.util.Map.of("quoteId", data.quoteId(), "attemptId", attemptId,
                        "providerPaymentId", providerId, "amount", data.amount(), "status", status));
        try {
            String payload = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(envelope);
            jdbc.update("INSERT INTO outbox_events(event_id,aggregate_id,event_type,payload,occurred_at) VALUES (?,?,?,?,?)",
                    eventId, data.quoteId(), type, payload, java.sql.Timestamp.from(now));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) { throw new IllegalStateException("Cannot serialize payment event", e); }
    }
    private record Attempt(BigDecimal amount, String status, String providerId, String quoteStatus,
            UUID quoteId, UUID orderId, UUID branchId, UUID sagaId, UUID correlationId) { }
}
