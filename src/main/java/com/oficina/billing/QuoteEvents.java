package com.oficina.billing;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
class QuoteEvents {
    private final ObjectMapper json;
    private final OutboxRepository repository;
    QuoteEvents(ObjectMapper json, OutboxRepository repository) { this.json = json; this.repository = repository; }
    void record(Quote quote, String type) {
        UUID eventId = UUID.randomUUID();
        Instant now = Instant.now();
        Map<String, Object> envelope = Map.of("eventId", eventId, "eventType", type, "schemaVersion", 1,
                "occurredAt", now.toString(), "sagaId", quote.getSagaId(), "osId", quote.getOrderId(),
                "filialId", quote.getBranchId(), "correlationId", quote.getCorrelationId(),
                "payload", Map.of("quoteId", quote.getId(), "amount", quote.getAmount(), "status", quote.getStatus()));
        try { repository.save(new OutboxEvent(eventId, quote.getId(), type, json.writeValueAsString(envelope), now)); }
        catch (JsonProcessingException e) { throw new IllegalStateException("Cannot serialize quote event", e); }
    }
}
