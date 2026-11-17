package com.oficina.billing;
import java.math.BigDecimal;
import java.util.UUID;
import jakarta.persistence.*;
@Entity
@Table(name = "quotes", uniqueConstraints = @UniqueConstraint(columnNames = {"branch_id", "order_id"}))
public class Quote {
    public enum Status { PENDING, APPROVED, REJECTED, CANCELLED }
    @Id private UUID id;
    @Column(name = "branch_id", nullable = false, updatable = false) private UUID branchId;
    @Column(name = "order_id", nullable = false, updatable = false) private UUID orderId;
    @Column(nullable = false, precision = 15, scale = 2, updatable = false) private BigDecimal amount;
    @Enumerated(EnumType.STRING) @Column(nullable = false) private Status status;
    @Version private long version;
    @Column(name = "saga_id", nullable = false, updatable = false) private UUID sagaId;
    @Column(name = "correlation_id", nullable = false, updatable = false) private UUID correlationId;
    protected Quote() {}
    Quote(UUID branchId, UUID orderId, BigDecimal amount) {
        this(branchId, orderId, amount, orderId, orderId);
    }
    Quote(UUID branchId, UUID orderId, BigDecimal amount, UUID sagaId, UUID correlationId) {
        this.id = UUID.randomUUID(); this.branchId = branchId; this.orderId = orderId;
        this.amount = amount; this.status = Status.PENDING;
        this.sagaId = sagaId; this.correlationId = correlationId;
    }
    void decide(Status decision) {
        if (decision != Status.APPROVED && decision != Status.REJECTED) throw new IllegalArgumentException("Invalid decision");
        if (status == decision) return;
        if (status != Status.PENDING) throw new IllegalStateException("Quote already decided");
        status = decision;
    }
    void cancel() {
        if (status == Status.CANCELLED) return;
        if (status != Status.APPROVED) throw new IllegalStateException("Only an approved quote can be compensated");
        status = Status.CANCELLED;
    }
    public UUID getId() { return id; }
    public UUID getBranchId() { return branchId; }
    public UUID getOrderId() { return orderId; }
    public BigDecimal getAmount() { return amount; }
    public Status getStatus() { return status; }
    public UUID getSagaId() { return sagaId; }
    public UUID getCorrelationId() { return correlationId; }
}
