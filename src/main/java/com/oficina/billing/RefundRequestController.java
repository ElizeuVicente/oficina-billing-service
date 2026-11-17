package com.oficina.billing;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/refund-requests")
class RefundRequestController {
    record Request(@NotNull UUID quoteId, @NotNull UUID paymentAttemptId,
            @NotNull @Pattern(regexp="[0-9]{1,20}") String providerPaymentId,
            @NotNull @Pattern(regexp="INSUFFICIENT_STOCK") String reason) { }
    private final JdbcTemplate jdbc;
    private final QuoteService quotes;
    RefundRequestController(JdbcTemplate jdbc, QuoteService quotes) { this.jdbc = jdbc; this.quotes = quotes; }
    @PutMapping("/{saga}") @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    void request(@PathVariable UUID saga, @Valid @RequestBody Request request) {
        quotes.get(request.quoteId());
        var payments = jdbc.query("""
                SELECT p.status FROM payment_attempts p JOIN quotes q ON q.id=p.quote_id
                WHERE q.id=? AND q.saga_id=? AND p.attempt_id=? AND p.provider_payment_id=? FOR UPDATE OF p
                """, (rs, i) -> rs.getString(1), request.quoteId(), saga, request.paymentAttemptId(), request.providerPaymentId());
        if (payments.size() != 1) throw new ResponseStatusException(HttpStatus.CONFLICT, "Payment does not match request");
        var existing = jdbc.query("SELECT quote_id,attempt_id,provider_payment_id,reason FROM refund_requests WHERE saga_id=?",
                (rs, i) -> new Request(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3), rs.getString(4)), saga);
        if (!existing.isEmpty()) {
            if (!existing.get(0).equals(request)) throw new ResponseStatusException(HttpStatus.CONFLICT, "Refund request conflicts");
            return;
        }
        if (!payments.get(0).equals("APPROVED")) throw new ResponseStatusException(HttpStatus.CONFLICT, "Payment must be approved");
        jdbc.update("INSERT INTO refund_requests(saga_id,quote_id,attempt_id,provider_payment_id,reason,status) VALUES (?,?,?,?,?,'REQUESTED')",
                saga, request.quoteId(), request.paymentAttemptId(), request.providerPaymentId(), request.reason());
    }
}
