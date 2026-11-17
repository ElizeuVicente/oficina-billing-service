package com.oficina.billing;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.client.JdkClientHttpRequestFactory;

/** Checkout preference creation is not proof of payment. */
@org.springframework.stereotype.Service
@ConditionalOnProperty(name="billing.mercadopago.checkout.enabled", havingValue="true")
class MercadoPagoCheckout {
    private static final Logger log = LoggerFactory.getLogger(MercadoPagoCheckout.class);
    private final PaymentAttemptService attempts;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final RestClient provider;
    private final String notificationUrl;

    @org.springframework.beans.factory.annotation.Autowired
    MercadoPagoCheckout(PaymentAttemptService attempts, JdbcTemplate jdbc, TransactionTemplate tx,
            @Value("${billing.mercadopago.access-token}") String token,
            @Value("${billing.mercadopago.notification-url}") String notificationUrl,
            @Value("${billing.mercadopago.reconciliation.enabled:false}") boolean reconciliation,
            @Value("${billing.mercadopago.webhook.enabled:false}") boolean webhook) {
        this(attempts, jdbc, tx, provider(token), notificationUrl);
        if (!reconciliation || !webhook) throw new IllegalStateException("Checkout requires webhook and provider reconciliation");
    }
    MercadoPagoCheckout(PaymentAttemptService attempts, JdbcTemplate jdbc, TransactionTemplate tx, RestClient provider, String notificationUrl) {
        URI url = URI.create(notificationUrl);
        if (!"https".equalsIgnoreCase(url.getScheme()) || url.getHost() == null || url.getUserInfo() != null || url.getFragment() != null)
            throw new IllegalArgumentException("Notification URL must use HTTPS");
        this.attempts = attempts; this.jdbc = jdbc; this.tx = tx; this.provider = provider; this.notificationUrl = notificationUrl;
    }
    private static RestClient provider(String token) {
        if (token == null || token.isBlank()) throw new IllegalArgumentException("Mercado Pago access token required");
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
        factory.setReadTimeout(Duration.ofSeconds(10));
        return RestClient.builder().baseUrl("https://api.mercadopago.com").requestFactory(factory).defaultHeaders(h -> h.setBearerAuth(token)).build();
    }
    Result create(UUID quoteId, UUID attemptId) {
        Claim claim = tx.execute(status -> {
            PaymentAttempt attempt = attempts.prepare(quoteId, attemptId);
            var rows = jdbc.query("SELECT state,preference_id,checkout_url,sandbox_url FROM checkout_preferences WHERE attempt_id=? FOR UPDATE",
                    (rs, i) -> new Result(attemptId, rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)), attemptId);
            if (!rows.isEmpty()) return new Claim(rows.get(0), null);
            jdbc.update("INSERT INTO checkout_preferences(attempt_id,state) VALUES (?,'DISPATCHING')", attemptId);
            return new Claim(null, attempt.amount());
        });
        if (claim.existing() != null) return claim.existing();
        try {
            var body = Map.of("items", List.of(Map.of("id", quoteId.toString(), "title", "Serviço de oficina",
                    "quantity", 1, "currency_id", "BRL", "unit_price", claim.amount())),
                    "external_reference", attemptId.toString(), "notification_url", notificationUrl);
            JsonNode response = provider.post().uri("/checkout/preferences").body(body).retrieve().body(JsonNode.class);
            if (response == null || !attemptId.toString().equals(response.path("external_reference").asText()))
                throw new IllegalStateException("Invalid preference reference");
            String preferenceId = response.path("id").asText();
            if (!preferenceId.matches("[A-Za-z0-9_-]{1,200}")) throw new IllegalStateException("Invalid preference ID");
            String checkout = verifiedUrl(response.path("init_point").asText());
            String sandbox = response.hasNonNull("sandbox_init_point") ? verifiedUrl(response.path("sandbox_init_point").asText()) : null;
            jdbc.update("UPDATE checkout_preferences SET state='READY',preference_id=?,checkout_url=?,sandbox_url=?,updated_at=CURRENT_TIMESTAMP WHERE attempt_id=? AND state='DISPATCHING'", preferenceId, checkout, sandbox, attemptId);
            return new Result(attemptId, "READY", preferenceId, checkout, sandbox);
        } catch (RuntimeException failure) {
            // A timeout or invalid response may still have created a preference; never retry blindly.
            log.warn("Checkout creation requires reconciliation: {}", failure.getClass().getSimpleName());
            jdbc.update("UPDATE checkout_preferences SET state='UNKNOWN',updated_at=CURRENT_TIMESTAMP WHERE attempt_id=? AND state='DISPATCHING'", attemptId);
            return new Result(attemptId, "UNKNOWN", null, null, null);
        }
    }
    Result status(UUID quoteId) {
        var rows = jdbc.query("""
                SELECT r.attempt_id, COALESCE(p.state,r.state), p.preference_id,p.checkout_url,p.sandbox_url
                FROM checkout_requests r LEFT JOIN checkout_preferences p ON p.attempt_id=r.attempt_id
                WHERE r.quote_id=?
                UNION ALL
                SELECT a.attempt_id, COALESCE(p.state,'PENDING'), p.preference_id,p.checkout_url,p.sandbox_url
                FROM payment_attempts a LEFT JOIN checkout_preferences p ON p.attempt_id=a.attempt_id
                WHERE a.quote_id=? AND NOT EXISTS (SELECT 1 FROM checkout_requests r WHERE r.quote_id=?)
                """, (rs, i) -> new Result(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5)),
                quoteId, quoteId, quoteId);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Checkout has not been requested");
        if (rows.size() != 1) throw new IllegalStateException("Conflicting checkout state");
        return rows.get(0);
    }
    private static String verifiedUrl(String value) {
        URI uri = URI.create(value);
        String host = uri.getHost();
        if (!"https".equalsIgnoreCase(uri.getScheme()) || host == null || uri.getUserInfo() != null
                || !(host.equals("mercadopago.com.br") || host.endsWith(".mercadopago.com.br") || host.equals("mercadopago.com") || host.endsWith(".mercadopago.com")))
            throw new IllegalStateException("Invalid checkout URL");
        return value;
    }
    record Result(UUID attemptId, String state, String preferenceId, String checkoutUrl, String sandboxUrl) { }
    private record Claim(Result existing, java.math.BigDecimal amount) { }
}

@RestController
@RequestMapping("/api/quotes/{quote}/checkout")
@ConditionalOnProperty(name="billing.mercadopago.checkout.enabled", havingValue="true")
class MercadoPagoCheckoutController {
    record Request(@NotNull UUID attemptId) { }
    private final MercadoPagoCheckout checkout;
    private final QuoteService quotes;
    MercadoPagoCheckoutController(MercadoPagoCheckout checkout, QuoteService quotes) { this.checkout = checkout; this.quotes = quotes; }
    @PostMapping @ResponseStatus(HttpStatus.ACCEPTED)
    MercadoPagoCheckout.Result create(@PathVariable UUID quote, @Valid @RequestBody Request request) {
        return checkout.create(quote, request.attemptId());
    }
    @GetMapping
    MercadoPagoCheckout.Result status(@PathVariable UUID quote) { quotes.get(quote); return checkout.status(quote); }
}
