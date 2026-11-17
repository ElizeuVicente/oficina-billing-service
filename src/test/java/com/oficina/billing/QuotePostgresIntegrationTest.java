package com.oficina.billing;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(properties = "billing.service-token=local-test-token-01234567890123456789")
@AutoConfigureMockMvc(addFilters = false)
@Testcontainers(disabledWithoutDocker = true)
class QuotePostgresIntegrationTest {
    @Container static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine");
    @DynamicPropertySource static void datasource(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", postgres::getJdbcUrl);
        properties.add("spring.datasource.username", postgres::getUsername);
        properties.add("spring.datasource.password", postgres::getPassword);
    }
    @Autowired QuoteService service;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired PaymentAttemptService paymentAttempts;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactionManager;

    @Test void checkoutIsCreatedOnceAndAmbiguousResponseDoesNotTriggerDuplicate() throws Exception {
        UUID attempt = UUID.randomUUID(), secondAttempt = UUID.randomUUID();
        var quote = service.create(new QuoteController.CreateQuote(UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("14.50"), null, null));
        var secondQuote = service.create(new QuoteController.CreateQuote(UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("20.00"), null, null));
        service.decide(quote.getId(), Quote.Status.APPROVED); service.decide(secondQuote.getId(), Quote.Status.APPROVED);
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/checkout/preferences", exchange -> {
            String request = new String(exchange.getRequestBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            calls.incrementAndGet();
            if (request.contains(secondAttempt.toString())) { exchange.sendResponseHeaders(503, -1); exchange.close(); return; }
            assertTrue(request.contains(attempt.toString())); assertTrue(request.contains("14.50"));
            byte[] body = ("{\"id\":\"123-abc\",\"external_reference\":\"" + attempt + "\",\"init_point\":\"https://www.mercadopago.com.br/checkout/abc\"}").getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json"); exchange.sendResponseHeaders(201, body.length); exchange.getResponseBody().write(body); exchange.close();
        });
        server.start();
        try {
            var client = org.springframework.web.client.RestClient.builder().baseUrl("http://127.0.0.1:" + server.getAddress().getPort()).build();
            var checkout = new MercadoPagoCheckout(paymentAttempts, jdbc, new org.springframework.transaction.support.TransactionTemplate(transactionManager), client, "https://example.org/webhooks/mercadopago");
            assertEquals("READY", checkout.create(quote.getId(), attempt).state());
            assertEquals("READY", checkout.create(quote.getId(), attempt).state());
            assertEquals("READY", checkout.status(quote.getId()).state());
            assertEquals("PENDING", jdbc.queryForObject("select status from payment_attempts where attempt_id=?", String.class, attempt));
            assertEquals("UNKNOWN", checkout.create(secondQuote.getId(), secondAttempt).state());
            assertEquals("UNKNOWN", checkout.create(secondQuote.getId(), secondAttempt).state());
            assertEquals(2, calls.get());
        } finally { server.stop(0); }
    }

    @Test void refundRequestIsIdempotentAndProviderSubmissionDoesNotConfirmPayment() throws Exception {
        UUID saga = UUID.randomUUID(), attempt = UUID.randomUUID();
        var quote = service.create(new QuoteController.CreateQuote(UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("14.50"), saga, saga));
        service.decide(quote.getId(), Quote.Status.APPROVED); paymentAttempts.prepare(quote.getId(), attempt);
        jdbc.update("UPDATE payment_attempts SET status='APPROVED',provider_payment_id='123456' WHERE attempt_id=?", attempt);
        String body = "{\"quoteId\":\"" + quote.getId() + "\",\"paymentAttemptId\":\"" + attempt + "\",\"providerPaymentId\":\"123456\",\"reason\":\"INSUFFICIENT_STOCK\"}";
        for (int i = 0; i < 2; i++) assertEquals(204, mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/refund-requests/" + saga).contentType(MediaType.APPLICATION_JSON).content(body)).andReturn().getResponse().getStatus());
        assertEquals(409, mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/refund-requests/" + saga).contentType(MediaType.APPLICATION_JSON).content(body.replace("123456", "123457"))).andReturn().getResponse().getStatus());
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        var failing = new java.util.concurrent.atomic.AtomicBoolean();
        var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/payments/123456/refunds", exchange -> {
            assertEquals(saga.toString(), exchange.getRequestHeaders().getFirst("X-Idempotency-Key"));
            calls.incrementAndGet(); exchange.sendResponseHeaders(failing.get() ? 503 : 201, -1); exchange.close();
        });
        server.start();
        try {
            var client = org.springframework.web.client.RestClient.builder().baseUrl("http://127.0.0.1:" + server.getAddress().getPort()).build();
            var tx = new org.springframework.transaction.support.TransactionTemplate(new org.springframework.jdbc.datasource.DataSourceTransactionManager(jdbc.getDataSource()));
            var worker = new MercadoPagoRefundDispatcher(jdbc, tx, client);
            worker.dispatch(); worker.dispatch();
            assertEquals(1, calls.get());
            assertEquals("SUBMITTED", jdbc.queryForObject("select status from refund_requests where saga_id=?", String.class, saga));
            assertEquals("APPROVED", jdbc.queryForObject("select status from payment_attempts where attempt_id=?", String.class, attempt));
            jdbc.update("UPDATE refund_requests SET status='REQUESTED' WHERE saga_id=?", saga);
            failing.set(true); worker.dispatch(); worker.dispatch();
            assertEquals(2, calls.get());
            assertEquals("UNKNOWN", jdbc.queryForObject("select status from refund_requests where saga_id=?", String.class, saga));
        } finally { server.stop(0); }
    }

    @Test void itemizedQuoteCalculatesTotalAndRejectsConflictingRetry() throws Exception {
        UUID branch = UUID.randomUUID(), order = UUID.randomUUID(), part = UUID.randomUUID();
        String body = "{\"branchId\":\"" + branch + "\",\"orderId\":\"" + order + "\",\"items\":[{\"type\":\"PART\",\"referenceId\":\"" + part + "\",\"quantity\":2,\"unitPrice\":10.00}]}";
        var result = mvc.perform(post("/api/quotes/itemized").contentType(MediaType.APPLICATION_JSON).content(body)).andReturn().getResponse();
        assertEquals(201, result.getStatus());
        UUID quote = UUID.fromString(mapper.readTree(result.getContentAsString()).get("id").asText());
        assertEquals(0, new BigDecimal("20.00").compareTo(service.get(quote).getAmount()));
        assertEquals(201, mvc.perform(post("/api/quotes/itemized").contentType(MediaType.APPLICATION_JSON).content(body)).andReturn().getResponse().getStatus());
        assertEquals(1, jdbc.queryForObject("select count(*) from quote_items where quote_id=?", Integer.class, quote));
        assertEquals(409, mvc.perform(post("/api/quotes/itemized").contentType(MediaType.APPLICATION_JSON).content(body.replace(part.toString(), UUID.randomUUID().toString()))).andReturn().getResponse().getStatus());
    }

    @Test void paymentIntentRequiresApprovalAndKeepsStableIdentity() {
        var quote = service.create(new QuoteController.CreateQuote(UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("14.50"), null, null));
        UUID attempt = UUID.randomUUID();
        assertThrows(org.springframework.web.server.ResponseStatusException.class, () -> paymentAttempts.prepare(quote.getId(), attempt));
        service.decide(quote.getId(), Quote.Status.APPROVED);
        var first = paymentAttempts.prepare(quote.getId(), attempt);
        var repeated = paymentAttempts.prepare(quote.getId(), attempt);
        assertEquals(first.id(), repeated.id());
        assertEquals("PENDING", repeated.status());
        assertEquals(0, new BigDecimal("14.50").compareTo(repeated.amount()));
        assertThrows(org.springframework.web.server.ResponseStatusException.class, () -> service.compensate(quote.getId()));
        assertThrows(org.springframework.web.server.ResponseStatusException.class, () -> paymentAttempts.prepare(quote.getId(), UUID.randomUUID()));
        assertEquals(1, jdbc.queryForObject("select count(*) from payment_attempts where quote_id=?", Integer.class, quote.getId()));
    }

    @Test void automaticCheckoutRequestIsPersistedOnlyOnce() {
        var quote = service.create(new QuoteController.CreateQuote(UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("14.50"), null, null));
        service.decide(quote.getId(), Quote.Status.APPROVED);
        var scheduler = new CheckoutRequestScheduler(jdbc, org.mockito.Mockito.mock(MercadoPagoCheckout.class));
        scheduler.schedule(quote.getId());
        scheduler.schedule(quote.getId());
        assertEquals(1, jdbc.queryForObject("select count(*) from checkout_requests where quote_id=?", Integer.class, quote.getId()));
        assertEquals("PENDING", jdbc.queryForObject("select state from checkout_requests where quote_id=?", String.class, quote.getId()));
    }

    @Test void automaticCheckoutRequestCreatesOnePreferenceAndPersistsItsLink() throws Exception {
        // The PostgreSQL container is shared by this class; do not process a request from another scenario.
        jdbc.update("DELETE FROM checkout_requests");
        var quote = service.create(new QuoteController.CreateQuote(UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("14.50"), null, null));
        service.decide(quote.getId(), Quote.Status.APPROVED);
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/checkout/preferences", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            String attempt = new com.fasterxml.jackson.databind.ObjectMapper().readTree(body).path("external_reference").asText();
            calls.incrementAndGet();
            byte[] response = ("{\"id\":\"pref-auto\",\"external_reference\":\"" + attempt + "\",\"init_point\":\"https://www.mercadopago.com.br/checkout/auto\"}").getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json"); exchange.sendResponseHeaders(201, response.length); exchange.getResponseBody().write(response); exchange.close();
        });
        server.start();
        try {
            var client = org.springframework.web.client.RestClient.builder().baseUrl("http://127.0.0.1:" + server.getAddress().getPort()).build();
            var checkout = new MercadoPagoCheckout(paymentAttempts, jdbc, new org.springframework.transaction.support.TransactionTemplate(transactionManager), client, "https://example.org/webhooks/mercadopago");
            var scheduler = new CheckoutRequestScheduler(jdbc, checkout);
            scheduler.schedule(quote.getId()); scheduler.schedule(quote.getId());
            scheduler.deliverPending(); scheduler.deliverPending();
            assertEquals(1, calls.get());
            assertEquals("READY", jdbc.queryForObject("select state from checkout_requests where quote_id=?", String.class, quote.getId()));
            assertEquals("https://www.mercadopago.com.br/checkout/auto", checkout.status(quote.getId()).checkoutUrl());
        } finally { server.stop(0); }
    }

    @Test void automaticCheckoutDoesNotRetryAnAmbiguousProviderFailure() throws Exception {
        jdbc.update("DELETE FROM checkout_requests");
        var quote = service.create(new QuoteController.CreateQuote(UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("14.50"), null, null));
        service.decide(quote.getId(), Quote.Status.APPROVED);
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/checkout/preferences", exchange -> { calls.incrementAndGet(); exchange.sendResponseHeaders(503, -1); exchange.close(); });
        server.start();
        try {
            var client = org.springframework.web.client.RestClient.builder().baseUrl("http://127.0.0.1:" + server.getAddress().getPort()).build();
            var checkout = new MercadoPagoCheckout(paymentAttempts, jdbc, new org.springframework.transaction.support.TransactionTemplate(transactionManager), client, "https://example.org/webhooks/mercadopago");
            var scheduler = new CheckoutRequestScheduler(jdbc, checkout);
            scheduler.schedule(quote.getId()); scheduler.deliverPending(); scheduler.deliverPending();
            assertEquals(1, calls.get());
            assertEquals("UNKNOWN", jdbc.queryForObject("select state from checkout_requests where quote_id=?", String.class, quote.getId()));
        } finally { server.stop(0); }
    }

    @Test void migrationsAllowCancellationAndPreserveOutbox() {
        var quote = service.create(new QuoteController.CreateQuote(UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("10.00"), null, null));
        service.decide(quote.getId(), Quote.Status.APPROVED);
        service.compensate(quote.getId());
        service.compensate(quote.getId());
        assertEquals("CANCELLED", jdbc.queryForObject("select status from quotes where id=?", String.class, quote.getId()));
        assertEquals(3, jdbc.queryForObject("select count(*) from outbox_events where aggregate_id=?", Integer.class, quote.getId()));
    }

    @Test void concurrentRequestsCreateOneQuoteAndOneEvent() throws Exception {
        UUID branch = UUID.randomUUID(), order = UUID.randomUUID();
        String body = "{\"branchId\":\"" + branch + "\",\"orderId\":\"" + order + "\",\"amount\":50.00}";
        var barrier = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            Callable<Integer> request = () -> {
                barrier.await();
                return mvc.perform(post("/api/quotes").contentType(MediaType.APPLICATION_JSON).content(body)).andReturn().getResponse().getStatus();
            };
            var first = pool.submit(request); var second = pool.submit(request); barrier.countDown();
            assertTrue(java.util.Set.of(201, 409).contains(first.get(30, TimeUnit.SECONDS)));
            assertTrue(java.util.Set.of(201, 409).contains(second.get(30, TimeUnit.SECONDS)));
            var retry = mvc.perform(post("/api/quotes").contentType(MediaType.APPLICATION_JSON).content(body)).andReturn().getResponse();
            assertEquals(201, retry.getStatus());
            UUID quoteId = UUID.fromString(mapper.readTree(retry.getContentAsString()).get("id").asText());
            assertEquals(1, jdbc.queryForObject("select count(*) from quotes where branch_id=? and order_id=?", Integer.class, branch, order));
            assertEquals(1, jdbc.queryForObject("select count(*) from outbox_events where aggregate_id=? and event_type='OrcamentoGerado'", Integer.class, quoteId));
        } finally { pool.shutdownNow(); }
    }
}
