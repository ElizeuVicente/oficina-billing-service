package com.oficina.billing;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

interface QuoteItemRepository extends JpaRepository<QuoteItem, UUID> {
    List<QuoteItem> findByQuoteIdOrderByPositionAsc(UUID quoteId);
}

@org.springframework.stereotype.Service
class ItemizedQuoteService {
    private final QuoteService quotes;
    private final QuoteItemRepository items;
    private final QuoteRepository quoteRepository;
    ItemizedQuoteService(QuoteService quotes, QuoteItemRepository items, QuoteRepository quoteRepository) { this.quotes = quotes; this.items = items; this.quoteRepository = quoteRepository; }
    @Transactional
    Quote create(QuoteItemsController.Request request) {
        BigDecimal amount = request.items().stream().map(i -> i.unitPrice().multiply(BigDecimal.valueOf(i.quantity())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (amount.signum() <= 0 || amount.compareTo(new BigDecimal("9999999999999.99")) > 0)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid quote total");
        boolean previouslyCreated = quoteRepository.findByBranchIdAndOrderId(request.branchId(), request.orderId()).isPresent();
        Quote quote = quotes.create(new QuoteController.CreateQuote(request.branchId(), request.orderId(), amount, request.sagaId(), request.correlationId()));
        var existing = items.findByQuoteIdOrderByPositionAsc(quote.getId());
        if (!existing.isEmpty()) {
            if (existing.size() != request.items().size()) throw new ResponseStatusException(HttpStatus.CONFLICT, "Quote items conflict");
            for (int i = 0; i < existing.size(); i++) if (!existing.get(i).matches(request.items().get(i)))
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Quote items conflict");
            return quote;
        }
        // Legacy quotes cannot acquire items after creation; that would rewrite an existing contract.
        if (previouslyCreated || quote.getStatus() != Quote.Status.PENDING) throw new ResponseStatusException(HttpStatus.CONFLICT, "Legacy quote has no items");
        for (int i = 0; i < request.items().size(); i++) items.save(new QuoteItem(quote.getId(), i, request.items().get(i)));
        return quote;
    }
    @Transactional(readOnly = true) List<QuoteItem> get(UUID quoteId) { quotes.get(quoteId); return items.findByQuoteIdOrderByPositionAsc(quoteId); }
}

@RestController
@RequestMapping("/api/quotes")
class QuoteItemsController {
    record Item(@NotNull @Pattern(regexp = "PART|SERVICE") String type, @NotNull UUID referenceId,
            @NotNull @Min(1) @Max(1000000) Integer quantity, @NotNull @DecimalMin("0.00") @Digits(integer = 13, fraction = 2) BigDecimal unitPrice) { }
    record Request(@NotNull UUID branchId, @NotNull UUID orderId, UUID sagaId, UUID correlationId,
            @NotEmpty @Size(max = 100) List<@NotNull @Valid Item> items) { }
    private final ItemizedQuoteService service;
    QuoteItemsController(ItemizedQuoteService service) { this.service = service; }
    @PostMapping("/itemized") @ResponseStatus(HttpStatus.CREATED) Quote create(@Valid @RequestBody Request request) {
        try { return service.create(request); }
        catch (org.springframework.dao.DataIntegrityViolationException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Concurrent quote creation; retry the same request", e);
        }
    }
    @GetMapping("/{id}/items") List<QuoteItem> items(@PathVariable UUID id) { return service.get(id); }
}
