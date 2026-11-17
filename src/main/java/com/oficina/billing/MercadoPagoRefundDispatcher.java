package com.oficina.billing;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;

@org.springframework.context.annotation.Configuration
@org.springframework.scheduling.annotation.EnableScheduling
@ConditionalOnProperty(name="billing.mercadopago.refunds.enabled", havingValue="true")
class RefundSchedulingConfiguration {
    RefundSchedulingConfiguration(@Value("${billing.mercadopago.reconciliation.enabled:false}") boolean reconciliation) {
        if (!reconciliation) throw new IllegalStateException("Refunds require provider reconciliation");
    }
}

/** A failed or interrupted request remains uncertain; reconciliation is required before retry. */
@Component
@ConditionalOnProperty(name="billing.mercadopago.refunds.enabled", havingValue="true")
class MercadoPagoRefundDispatcher {
    private static final Logger log = LoggerFactory.getLogger(MercadoPagoRefundDispatcher.class);
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final RestClient provider;
    @org.springframework.beans.factory.annotation.Autowired
    MercadoPagoRefundDispatcher(JdbcTemplate jdbc, TransactionTemplate tx, @Value("${billing.mercadopago.access-token}") String token) {
        this(jdbc, tx, provider(token));
    }
    MercadoPagoRefundDispatcher(JdbcTemplate jdbc, TransactionTemplate tx, RestClient provider) {
        this.jdbc = jdbc; this.tx = tx; this.provider = provider;
    }
    private static RestClient provider(String token) {
        if (token == null || token.isBlank()) throw new IllegalArgumentException("Mercado Pago access token required");
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
        factory.setReadTimeout(Duration.ofSeconds(10));
        return RestClient.builder().baseUrl("https://api.mercadopago.com").requestFactory(factory).defaultHeaders(h -> h.setBearerAuth(token)).build();
    }
    @Scheduled(fixedDelayString="${billing.mercadopago.refunds.delay-ms:30000}")
    public void dispatch() {
        var ids = jdbc.query("SELECT saga_id FROM refund_requests WHERE status='REQUESTED' ORDER BY created_at LIMIT 20", (rs, i) -> rs.getObject(1, UUID.class));
        for (UUID id : ids) {
            String payment = tx.execute(status -> {
                var rows = jdbc.query("SELECT provider_payment_id FROM refund_requests WHERE saga_id=? AND status='REQUESTED' FOR UPDATE SKIP LOCKED", (rs, i) -> rs.getString(1), id);
                if (rows.isEmpty()) return null;
                jdbc.update("UPDATE refund_requests SET status='DISPATCHING',updated_at=CURRENT_TIMESTAMP WHERE saga_id=?", id);
                return rows.get(0);
            });
            if (payment == null) continue;
            try {
                provider.post().uri("/v1/payments/{id}/refunds", payment).header("X-Idempotency-Key", id.toString())
                        .contentType(MediaType.APPLICATION_JSON).body("{}").retrieve().toBodilessEntity();
                jdbc.update("UPDATE refund_requests SET status='SUBMITTED',updated_at=CURRENT_TIMESTAMP WHERE saga_id=? AND status='DISPATCHING'", id);
            } catch (RuntimeException e) {
                log.warn("Refund request requires reconciliation: {}", e.getClass().getSimpleName());
                jdbc.update("UPDATE refund_requests SET status='UNKNOWN',updated_at=CURRENT_TIMESTAMP WHERE saga_id=? AND status='DISPATCHING'", id);
            }
        }
    }
}
