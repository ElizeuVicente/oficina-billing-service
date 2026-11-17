package com.oficina.billing;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import jakarta.persistence.*;

/** Persistent intent only; it is not evidence that a payment exists at the provider. */
@Entity
@Table(name = "payment_attempts")
class PaymentAttempt {
    @Id @Column(name = "attempt_id") private UUID id;
    @Column(name = "quote_id", nullable = false, unique = true, updatable = false) private UUID quoteId;
    @Column(nullable = false, precision = 15, scale = 2, updatable = false) private BigDecimal amount;
    @Column(nullable = false, length = 32) private String status;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;
    protected PaymentAttempt() { }
    PaymentAttempt(UUID id, Quote quote) {
        this.id = id; this.quoteId = quote.getId(); this.amount = quote.getAmount();
        this.status = "PENDING"; this.createdAt = Instant.now();
    }
    UUID id() { return id; }
    UUID quoteId() { return quoteId; }
    BigDecimal amount() { return amount; }
    String status() { return status; }
}
