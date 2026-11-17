package com.oficina.billing;

import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Decouples quote approval from the provider call. A lost HTTP response is left for
 * reconciliation instead of being retried as a potentially duplicate charge.
 */
@Component
@ConditionalOnProperty(name = "billing.mercadopago.checkout.auto-enabled", havingValue = "true")
class CheckoutRequestScheduler {
    private final JdbcTemplate jdbc;
    private final MercadoPagoCheckout checkout;

    CheckoutRequestScheduler(JdbcTemplate jdbc, MercadoPagoCheckout checkout) {
        this.jdbc = jdbc;
        this.checkout = checkout;
    }

    @Transactional
    void schedule(UUID quoteId) {
        jdbc.update("INSERT INTO checkout_requests(quote_id,attempt_id,state) VALUES (?,?,'PENDING') ON CONFLICT (quote_id) DO NOTHING",
                quoteId, UUID.randomUUID());
    }

    @Scheduled(fixedDelayString = "${billing.mercadopago.checkout.auto-delay-ms:5000}")
    public void deliverPending() {
        List<Request> requests = jdbc.query("""
                SELECT quote_id,attempt_id FROM checkout_requests
                WHERE state='PENDING' ORDER BY created_at LIMIT 20
                """, (rs, row) -> new Request(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class)));
        for (Request request : requests) {
            // Claim before the remote call. A crash or timeout stays UNKNOWN and is never retried blindly.
            int claimed = jdbc.update("UPDATE checkout_requests SET state='DISPATCHING',updated_at=CURRENT_TIMESTAMP WHERE quote_id=? AND state='PENDING'",
                    request.quoteId());
            if (claimed != 1) continue;
            MercadoPagoCheckout.Result result = checkout.create(request.quoteId(), request.attemptId());
            String state = "READY".equals(result.state()) ? "READY" : "UNKNOWN";
            jdbc.update("UPDATE checkout_requests SET state=?,updated_at=CURRENT_TIMESTAMP WHERE quote_id=? AND state='DISPATCHING'",
                    state, request.quoteId());
        }
    }

    private record Request(UUID quoteId, UUID attemptId) { }
}
