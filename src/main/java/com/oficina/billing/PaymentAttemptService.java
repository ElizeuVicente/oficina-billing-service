package com.oficina.billing;

import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
class PaymentAttemptService {
    private final QuoteRepository quotes;
    private final PaymentAttemptRepository attempts;
    private final BillingBranchPolicy branchPolicy;
    PaymentAttemptService(QuoteRepository quotes, PaymentAttemptRepository attempts, BillingBranchPolicy branchPolicy) { this.quotes = quotes; this.attempts = attempts; this.branchPolicy = branchPolicy; }

    @Transactional
    PaymentAttempt prepare(UUID quoteId, UUID attemptId) {
        Quote quote = quotes.findLockedById(quoteId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        branchPolicy.check(quote.getBranchId());
        if (quote.getStatus() != Quote.Status.APPROVED) throw new ResponseStatusException(HttpStatus.CONFLICT, "Quote must be approved");
        var existing = attempts.findByQuoteId(quoteId);
        if (existing.isPresent()) {
            if (!existing.get().id().equals(attemptId)) throw new ResponseStatusException(HttpStatus.CONFLICT, "Quote already has a payment attempt");
            return existing.get();
        }
        if (attempts.existsById(attemptId)) throw new ResponseStatusException(HttpStatus.CONFLICT, "Attempt ID belongs to another quote");
        // Checkout writes through JDBC in the same transaction and needs the FK row flushed first.
        return attempts.saveAndFlush(new PaymentAttempt(attemptId, quote));
    }
}
