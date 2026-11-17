package com.oficina.billing;
import java.math.BigDecimal;
import java.util.UUID;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("/api/quotes")
class QuoteController {
    record CreateQuote(@NotNull UUID branchId, @NotNull UUID orderId, @NotNull @DecimalMin("0.01") @Digits(integer = 13, fraction = 2) BigDecimal amount, UUID sagaId, UUID correlationId) {}
    record Decision(@NotNull Quote.Status status) {}
    private final QuoteService service;
    QuoteController(QuoteService service) { this.service = service; }
    @PostMapping @ResponseStatus(HttpStatus.CREATED) Quote create(@Valid @RequestBody CreateQuote request) {
        try { return service.create(request); }
        catch (org.springframework.dao.DataIntegrityViolationException e) {
            throw new org.springframework.web.server.ResponseStatusException(HttpStatus.CONFLICT, "Concurrent quote creation; retry the same request", e);
        }
    }
    @GetMapping("/{id}") Quote get(@PathVariable UUID id) { return service.get(id); }
    @PostMapping("/{id}/decision") Quote decide(@PathVariable UUID id, @Valid @RequestBody Decision decision) { return service.decide(id, decision.status()); }
}
