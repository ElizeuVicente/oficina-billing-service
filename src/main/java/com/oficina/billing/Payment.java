package com.oficina.billing;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;

/** Local state only: external payment and refund must be confirmed by the provider. */
public final class Payment {
    public enum Status { PENDING, APPROVED, REJECTED, REFUND_PENDING, REFUNDED }
    private final UUID attemptId;
    private final BigDecimal amount;
    private Status status = Status.PENDING;

    public Payment(UUID attemptId, BigDecimal amount) {
        this.attemptId = Objects.requireNonNull(attemptId);
        this.amount = Objects.requireNonNull(amount);
        if (amount.signum() <= 0 || amount.scale() > 2) throw new IllegalArgumentException("Invalid amount");
    }
    public UUID attemptId() { return attemptId; }
    public BigDecimal amount() { return amount; }
    public Status status() { return status; }
    public void confirmApproved() { transition(Status.PENDING, Status.APPROVED); }
    public void confirmRejected() { transition(Status.PENDING, Status.REJECTED); }
    public void requestRefund() { transition(Status.APPROVED, Status.REFUND_PENDING); }
    public void confirmRefund() { transition(Status.REFUND_PENDING, Status.REFUNDED); }
    private void transition(Status from, Status to) {
        if (status == to) return;
        if (status != from) throw new IllegalStateException("Invalid payment transition: " + status + " -> " + to);
        status = to;
    }
}
