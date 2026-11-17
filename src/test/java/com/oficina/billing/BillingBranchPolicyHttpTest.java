package com.oficina.billing;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:billing-branches;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa", "spring.datasource.password=", "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop", "billing.service-token=local-test-token-01234567890123456789",
        "billing.allowed-branches=11111111-1111-4111-8111-111111111111"})
@AutoConfigureMockMvc(addFilters = false)
class BillingBranchPolicyHttpTest {
    @Autowired MockMvc mvc;
    @Autowired QuoteRepository repository;

    @Test void createReadAndDecisionRejectUnlistedBranch() throws Exception {
        UUID allowed = UUID.fromString("11111111-1111-4111-8111-111111111111"), denied = UUID.randomUUID();
        var existing = repository.saveAndFlush(new Quote(denied, UUID.randomUUID(), new BigDecimal("10.00")));
        String body = "{\"branchId\":\"" + allowed + "\",\"orderId\":\"" + UUID.randomUUID() + "\",\"amount\":10.00}";
        mvc.perform(post("/api/quotes").contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isCreated());
        mvc.perform(post("/api/quotes").contentType(MediaType.APPLICATION_JSON).content(body.replace(allowed.toString(), denied.toString())))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/quotes/{id}", existing.getId())).andExpect(status().isForbidden());
        mvc.perform(get("/api/quotes/{id}/items", existing.getId())).andExpect(status().isForbidden());
        mvc.perform(post("/api/quotes/{id}/decision", existing.getId()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"APPROVED\"}")).andExpect(status().isForbidden());
    }
}
