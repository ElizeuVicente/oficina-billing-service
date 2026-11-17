package com.oficina.billing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(properties = {"billing.service-token=local-test-token-01234567890123456789",
        "billing.mercadopago.webhook.enabled=true", "billing.mercadopago.webhook.secret=test-secret-000000000000000000000000"})
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class MercadoPagoWebhookPostgresTest {
    @Container static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine");
    @DynamicPropertySource static void datasource(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", postgres::getJdbcUrl);
        properties.add("spring.datasource.username", postgres::getUsername);
        properties.add("spring.datasource.password", postgres::getPassword);
    }
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired QuoteService quotes;
    @Autowired PaymentAttemptService paymentAttempts;
    private String signature(long timestamp) throws Exception {
        var mac = javax.crypto.Mac.getInstance("HmacSHA256");
        mac.init(new javax.crypto.spec.SecretKeySpec("test-secret-000000000000000000000000".getBytes(java.nio.charset.StandardCharsets.UTF_8), "HmacSHA256"));
        String manifest = "id:123456;request-id:req-123;ts:" + timestamp + ";";
        return "ts=" + timestamp + ",v1=" + java.util.HexFormat.of().formatHex(mac.doFinal(manifest.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    @Test void validatesAndPersistsWithoutConfirmingPayment() throws Exception {
        String signature = signature(java.time.Instant.now().getEpochSecond());
        Integer paymentsBefore = jdbc.queryForObject("select count(*) from payment_attempts", Integer.class);
        mvc.perform(post("/webhooks/mercadopago").param("data.id", "123456"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/webhooks/mercadopago").param("data.id", "123457")
                .header("x-signature", signature).header("x-request-id", "req-123"))
                .andExpect(status().isUnauthorized());
        assertEquals(0, jdbc.queryForObject("select count(*) from mercadopago_notifications where request_id='req-123'", Integer.class));
        for (int i = 0; i < 2; i++) {
            mvc.perform(post("/webhooks/mercadopago").param("data.id", "123456")
                    .header("x-signature", signature).header("x-request-id", "req-123"))
                    .andExpect(status().isOk());
        }
        assertEquals(1, jdbc.queryForObject("select count(*) from mercadopago_notifications where request_id='req-123' and processed_at is null", Integer.class));
        // Already accepted request IDs may be retried late, but a new stale signature is rejected.
        mvc.perform(post("/webhooks/mercadopago").param("data.id", "123456")
                .header("x-signature", signature(1700000000L)).header("x-request-id", "req-123"))
                .andExpect(status().isOk());
        jdbc.update("DELETE FROM mercadopago_notifications WHERE request_id=?", "req-123");
        mvc.perform(post("/webhooks/mercadopago").param("data.id", "123456")
                .header("x-signature", signature(1700000000L)).header("x-request-id", "req-123"))
                .andExpect(status().isUnauthorized());
        assertEquals(paymentsBefore, jdbc.queryForObject("select count(*) from payment_attempts", Integer.class));
        mvc.perform(get("/api/quotes/00000000-0000-4000-8000-000000000001"))
                .andExpect(status().isUnauthorized());
    }

    @Test void reconcilesOnlyMatchingProviderDataAndRetriesFailures() throws Exception {
        var quote = quotes.create(new QuoteController.CreateQuote(java.util.UUID.randomUUID(), java.util.UUID.randomUUID(), new java.math.BigDecimal("42.50"), null, null));
        quotes.decide(quote.getId(), Quote.Status.APPROVED);
        var attempt = java.util.UUID.randomUUID(); paymentAttempts.prepare(quote.getId(), attempt);
        String requestId = "reconciliation-" + java.util.UUID.randomUUID();
        jdbc.update("INSERT INTO mercadopago_notifications(request_id,resource_id) VALUES (?,?)", requestId, "123456");
        var body = new java.util.concurrent.atomic.AtomicReference<>("{\"id\":123456,\"collector_id\":111,\"live_mode\":false,\"currency_id\":\"BRL\",\"external_reference\":\"" + attempt + "\",\"transaction_amount\":\"43.50\",\"status\":\"approved\"}");
        var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/payments/123456", exchange -> {
            byte[] bytes = body.get().getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
        });
        server.start();
        try {
            var provider = org.springframework.web.client.RestClient.builder().baseUrl("http://127.0.0.1:" + server.getAddress().getPort()).build();
            var tx = new org.springframework.transaction.support.TransactionTemplate(new org.springframework.jdbc.datasource.DataSourceTransactionManager(jdbc.getDataSource()));
            var reconciler = new MercadoPagoReconciliation(jdbc, tx, provider, "111", false);
            reconciler.processPending();
            assertEquals("PENDING", jdbc.queryForObject("select status from payment_attempts where attempt_id=?", String.class, attempt));
            assertEquals(1, jdbc.queryForObject("select attempts from mercadopago_notifications where request_id=?", Integer.class, requestId));
            body.set(body.get().replace("\"collector_id\":111", "\"collector_id\":222").replace("43.50", "42.50"));
            reconciler.processPending();
            assertEquals("PENDING", jdbc.queryForObject("select status from payment_attempts where attempt_id=?", String.class, attempt));
            String otherAttempt = java.util.UUID.randomUUID().toString();
            body.set(body.get().replace("\"collector_id\":222", "\"collector_id\":111").replace(attempt.toString(), otherAttempt));
            reconciler.processPending();
            assertEquals("PENDING", jdbc.queryForObject("select status from payment_attempts where attempt_id=?", String.class, attempt));
            body.set(body.get().replace(otherAttempt, attempt.toString()));
            body.set(body.get().replace("\"live_mode\":false", "\"live_mode\":true"));
            reconciler.processPending();
            assertEquals("PENDING", jdbc.queryForObject("select status from payment_attempts where attempt_id=?", String.class, attempt));
            body.set(body.get().replace("\"live_mode\":true", "\"live_mode\":false"));
            reconciler.processPending();
            assertEquals("APPROVED", jdbc.queryForObject("select status from payment_attempts where attempt_id=?", String.class, attempt));
            assertEquals("123456", jdbc.queryForObject("select provider_payment_id from payment_attempts where attempt_id=?", String.class, attempt));
            assertEquals(1, jdbc.queryForObject("select count(*) from outbox_events where aggregate_id=? and event_type='PagamentoConfirmado'", Integer.class, quote.getId()));
            assertEquals(1, jdbc.queryForObject("select count(*) from mercadopago_notifications where request_id=? and processed_at is not null", Integer.class, requestId));
            reconciler.processPending();
            assertEquals(5, jdbc.queryForObject("select attempts from mercadopago_notifications where request_id=?", Integer.class, requestId));
            assertEquals(1, jdbc.queryForObject("select count(*) from outbox_events where aggregate_id=? and event_type='PagamentoConfirmado'", Integer.class, quote.getId()));
            String refundRequest = "refund-" + java.util.UUID.randomUUID();
            jdbc.update("INSERT INTO refund_requests(saga_id,quote_id,attempt_id,provider_payment_id,reason,status) VALUES (?,?,?,?,?,'UNKNOWN')", java.util.UUID.randomUUID(), quote.getId(), attempt, "123456", "INSUFFICIENT_STOCK");
            jdbc.update("INSERT INTO mercadopago_notifications(request_id,resource_id) VALUES (?,?)", refundRequest, "123456");
            body.set(body.get().replace("\"status\":\"approved\"", "\"status\":\"refunded\""));
            reconciler.processPending();
            assertEquals("REFUNDED", jdbc.queryForObject("select status from payment_attempts where attempt_id=?", String.class, attempt));
            assertEquals("CONFIRMED", jdbc.queryForObject("select status from refund_requests where attempt_id=?", String.class, attempt));
            assertEquals(1, jdbc.queryForObject("select count(*) from outbox_events where aggregate_id=? and event_type='PagamentoEstornado'", Integer.class, quote.getId()));
            reconciler.processPending();
            assertEquals(1, jdbc.queryForObject("select count(*) from outbox_events where aggregate_id=? and event_type='PagamentoEstornado'", Integer.class, quote.getId()));
        } finally { server.stop(0); }
    }
}
