package com.oficina.billing;

import java.util.Arrays;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/** Limits the shared service credential as a whole; not a per-user authorization system. */
@Component
class BillingBranchPolicy {
    private final Set<UUID> allowed;
    BillingBranchPolicy(@Value("${billing.allowed-branches:}") String branches) {
        allowed = branches.isBlank() ? Set.of() : Arrays.stream(branches.split(",", -1))
                .map(String::trim).map(UUID::fromString).collect(java.util.stream.Collectors.toUnmodifiableSet());
    }
    void check(UUID branch) {
        if (branch == null || (!allowed.isEmpty() && !allowed.contains(branch)))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Branch not allowed");
    }
}
