package com.oficina.billing;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** Stores a signed notification for later provider reconciliation; never confirms payment. */
@RestController
@ConditionalOnProperty(name = "billing.mercadopago.webhook.enabled", havingValue = "true")
class MercadoPagoWebhookController {
    private final JdbcTemplate jdbc;
    private final String secret;
    private final long maxAgeSeconds;
    private final long futureSkewSeconds;

    MercadoPagoWebhookController(JdbcTemplate jdbc, @Value("${billing.mercadopago.webhook.secret}") String secret,
            @Value("${billing.mercadopago.webhook.max-age-seconds:900}") long maxAgeSeconds,
            @Value("${billing.mercadopago.webhook.future-skew-seconds:60}") long futureSkewSeconds) {
        if (secret == null || secret.isBlank()) throw new IllegalArgumentException("Mercado Pago webhook secret is required");
        if (maxAgeSeconds <= 0 || maxAgeSeconds > 86400 || futureSkewSeconds < 0 || futureSkewSeconds > 300)
            throw new IllegalArgumentException("Invalid webhook timestamp limits");
        this.jdbc = jdbc; this.secret = secret;
        this.maxAgeSeconds = maxAgeSeconds; this.futureSkewSeconds = futureSkewSeconds;
    }

    @PostMapping("/webhooks/mercadopago")
    @ResponseStatus(HttpStatus.OK)
    @Transactional
    public void receive(@RequestHeader(value = "x-signature", required = false) String signature,
            @RequestHeader(value = "x-request-id", required = false) String requestId,
            @RequestParam(value = "data.id", required = false) String[] ids) {
        if (ids == null || ids.length != 1 || !ids[0].matches("[0-9]{1,20}") || !MercadoPagoWebhookSignature.verify(secret, signature, requestId, ids[0]))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid webhook signature");
        var existing = jdbc.query("SELECT resource_id FROM mercadopago_notifications WHERE request_id=?",
                rs -> rs.next() ? rs.getString(1) : null, requestId);
        if (existing != null) {
            if (!ids[0].equals(existing)) throw new ResponseStatusException(HttpStatus.CONFLICT, "Notification identifier conflict");
            return; // already durably accepted; allow the provider's delayed delivery retry
        }
        if (!MercadoPagoWebhookSignature.isFresh(signature, java.time.Instant.now(), maxAgeSeconds, futureSkewSeconds))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Expired webhook signature");
        int inserted = jdbc.update("""
                INSERT INTO mercadopago_notifications(request_id, resource_id) VALUES (?,?)
                ON CONFLICT (request_id) DO NOTHING
                """, requestId, ids[0]);
        if (inserted == 0) {
            String concurrentResource = jdbc.queryForObject("SELECT resource_id FROM mercadopago_notifications WHERE request_id=?", String.class, requestId);
            if (!ids[0].equals(concurrentResource)) throw new ResponseStatusException(HttpStatus.CONFLICT, "Notification identifier conflict");
        }
    }
}
