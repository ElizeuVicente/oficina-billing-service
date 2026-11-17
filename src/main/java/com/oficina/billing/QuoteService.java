package com.oficina.billing;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
@Service
class QuoteService {
    private final QuoteRepository repository;
    private final QuoteEvents events;
    private final PaymentAttemptRepository attempts;
    private final ObjectProvider<CheckoutRequestScheduler> checkoutRequests;
    private final BillingBranchPolicy branchPolicy;
    QuoteService(QuoteRepository repository, QuoteEvents events, PaymentAttemptRepository attempts,
            ObjectProvider<CheckoutRequestScheduler> checkoutRequests, BillingBranchPolicy branchPolicy) {
        this.repository = repository; this.events = events; this.attempts = attempts; this.checkoutRequests = checkoutRequests;
        this.branchPolicy = branchPolicy;
    }
    @Transactional Quote create(QuoteController.CreateQuote request) {
        branchPolicy.check(request.branchId());
        UUID saga = request.sagaId() == null ? request.orderId() : request.sagaId();
        UUID correlation = request.correlationId() == null ? request.orderId() : request.correlationId();
        var existing = repository.findByBranchIdAndOrderId(request.branchId(), request.orderId());
        if (existing.isPresent()) {
            Quote quote = existing.get();
            if (quote.getAmount().compareTo(request.amount()) != 0 || !quote.getSagaId().equals(saga)
                    || !quote.getCorrelationId().equals(correlation))
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Quote already exists with different data");
            return quote;
        }
        Quote quote = repository.save(new Quote(request.branchId(), request.orderId(), request.amount(),
                saga, correlation));
        events.record(quote, "OrcamentoGerado");
        return quote;
    }
    @Transactional(readOnly = true) Quote get(UUID id) {
        Quote quote = repository.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        branchPolicy.check(quote.getBranchId()); return quote;
    }
    @Transactional Quote decide(UUID id, Quote.Status decision) {
        Quote quote = repository.findLockedById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        branchPolicy.check(quote.getBranchId());
        boolean changed = quote.getStatus() != decision;
        try { quote.decide(decision); } catch (IllegalStateException | IllegalArgumentException e) { throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage(), e); }
        if (changed) {
            events.record(quote, decision == Quote.Status.APPROVED ? "OrcamentoAprovado" : "OrcamentoRecusado");
            if (decision == Quote.Status.APPROVED) {
                // Persisted in this transaction; the provider is never called from the decision request.
                checkoutRequests.ifAvailable(requests -> requests.schedule(quote.getId()));
            }
        }
        return quote;
    }
    @Transactional Quote compensate(UUID id) {
        Quote quote = repository.findLockedById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        branchPolicy.check(quote.getBranchId());
        if (attempts.findByQuoteId(id).isPresent())
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Payment attempt requires provider reconciliation before quote cancellation");
        boolean changed = quote.getStatus() != Quote.Status.CANCELLED;
        try { quote.cancel(); } catch (IllegalStateException e) { throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage(), e); }
        if (changed) events.record(quote, "OrcamentoCancelado");
        return quote;
    }
}
