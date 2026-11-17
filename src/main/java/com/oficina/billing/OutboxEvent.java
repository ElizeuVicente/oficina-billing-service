package com.oficina.billing;

import java.time.Instant;
import java.util.UUID;
import jakarta.persistence.*;

@Entity
@Table(name = "outbox_events")
class OutboxEvent {
    @Id @Column(name = "event_id") private UUID eventId;
    @Column(name = "aggregate_id", nullable = false) private UUID aggregateId;
    @Column(name = "event_type", nullable = false, length = 100) private String eventType;
    @Column(nullable = false, columnDefinition = "text") private String payload;
    @Column(name = "occurred_at", nullable = false) private Instant occurredAt;
    @Column(name = "published_at") private Instant publishedAt;
    protected OutboxEvent() {}
    OutboxEvent(UUID eventId, UUID aggregateId, String eventType, String payload, Instant occurredAt) {
        this.eventId = eventId; this.aggregateId = aggregateId; this.eventType = eventType;
        this.payload = payload; this.occurredAt = occurredAt;
    }
    public String getPayload() { return payload; }
    UUID getEventId() { return eventId; }
    String getEventType() { return eventType; }
    void markPublished() { publishedAt = Instant.now(); }
}
