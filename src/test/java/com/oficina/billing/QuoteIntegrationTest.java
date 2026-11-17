package com.oficina.billing;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc(addFilters = false)
// H2 generates a different name for V1's unnamed CHECK constraint than PostgreSQL.
// Schema creation here tests application behavior; PostgreSQL migrations need their own integration test.
@TestPropertySource(properties = {"spring.datasource.url=jdbc:h2:mem:billing;MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "spring.datasource.username=sa", "spring.datasource.password=", "spring.flyway.enabled=false", "spring.jpa.hibernate.ddl-auto=create-drop", "billing.service-token=local-test-token-01234567890123456789"})
class QuoteIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired OutboxRepository outbox;
    @Autowired QuoteService service;
    @Autowired org.springframework.transaction.support.TransactionTemplate transactions;

    @Test void exposesOpenApiForTheService() throws Exception {
        mvc.perform(get("/v3/api-docs")).andExpect(status().isOk()).andExpect(jsonPath("$.paths['/api/quotes']").exists());
    }

    @Test void quotePersistsAndConflictingDecisionIsRejected() throws Exception {
        String body = "{\"branchId\":\"" + UUID.randomUUID() + "\",\"orderId\":\"" + UUID.randomUUID() + "\",\"amount\":30.50}";
        String result = mvc.perform(post("/api/quotes").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("PENDING"))
                .andReturn().getResponse().getContentAsString();
        String id = json.readTree(result).get("id").asText();
        String approved = "{\"status\":\"APPROVED\"}";
        mvc.perform(post("/api/quotes/{id}/decision", id).contentType(MediaType.APPLICATION_JSON).content(approved)).andExpect(status().isOk());
        mvc.perform(post("/api/quotes/{id}/decision", id).contentType(MediaType.APPLICATION_JSON).content(approved)).andExpect(status().isOk());
        mvc.perform(post("/api/quotes/{id}/decision", id).contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"REJECTED\"}"))
                .andExpect(status().isConflict());
        mvc.perform(get("/api/quotes/{id}", id)).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("APPROVED"));
        var events = outbox.findByAggregateId(UUID.fromString(id));
        assertEquals(2, events.size());
        var decisionEvent = events.stream().map(OutboxEvent::getPayload).filter(payload -> payload.contains("OrcamentoAprovado")).findFirst().orElseThrow();
        assertEquals(1, json.readTree(decisionEvent).get("schemaVersion").asInt());
        assertEquals(id, json.readTree(decisionEvent).get("payload").get("quoteId").asText());
    }
    @Test void transactionFailureRollsBackDecisionAndEventTogether() {
        var quote = service.create(new QuoteController.CreateQuote(UUID.randomUUID(), UUID.randomUUID(), new java.math.BigDecimal("10.00"), null, null));
        assertThrows(IllegalStateException.class, () -> transactions.executeWithoutResult(status -> {
            service.decide(quote.getId(), Quote.Status.APPROVED);
            throw new IllegalStateException("Simulated transaction failure");
        }));
        assertEquals(Quote.Status.PENDING, service.get(quote.getId()).getStatus());
        assertEquals(1, outbox.findByAggregateId(quote.getId()).size());
    }
    @Test void approvedQuoteCanBeCompensatedIdempotently() {
        var quote = service.create(new QuoteController.CreateQuote(UUID.randomUUID(), UUID.randomUUID(), new java.math.BigDecimal("10.00"), null, null));
        service.decide(quote.getId(), Quote.Status.APPROVED);
        service.compensate(quote.getId());
        service.compensate(quote.getId());
        assertEquals(Quote.Status.CANCELLED, service.get(quote.getId()).getStatus());
        assertEquals(3, outbox.findByAggregateId(quote.getId()).size());
    }
    @Test void invalidAmountIsRejected() throws Exception {
        String body = "{\"branchId\":\"" + UUID.randomUUID() + "\",\"orderId\":\"" + UUID.randomUUID() + "\",\"amount\":0}";
        mvc.perform(post("/api/quotes").contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());
    }
    @Test void cancellationIsNotAClientDecision() {
        var quote = service.create(new QuoteController.CreateQuote(UUID.randomUUID(), UUID.randomUUID(), new java.math.BigDecimal("10.00"), null, null));
        assertThrows(org.springframework.web.server.ResponseStatusException.class, () -> service.decide(quote.getId(), Quote.Status.CANCELLED));
        assertEquals(Quote.Status.PENDING, service.get(quote.getId()).getStatus());
        assertEquals(1, outbox.findByAggregateId(quote.getId()).size());
    }
    @Test void duplicateCreationReturnsSameQuoteAndRejectsDifferentAmount() throws Exception {
        UUID branch = UUID.randomUUID(), order = UUID.randomUUID();
        String body = "{\"branchId\":\"" + branch + "\",\"orderId\":\"" + order + "\",\"amount\":10.00}";
        String first = mvc.perform(post("/api/quotes").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String second = mvc.perform(post("/api/quotes").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        UUID id = UUID.fromString(json.readTree(first).get("id").asText());
        assertEquals(id.toString(), json.readTree(second).get("id").asText());
        assertEquals(1, outbox.findByAggregateId(id).size());
        mvc.perform(post("/api/quotes").contentType(MediaType.APPLICATION_JSON).content(body.replace("10.00", "11.00")))
                .andExpect(status().isConflict());
    }
}
