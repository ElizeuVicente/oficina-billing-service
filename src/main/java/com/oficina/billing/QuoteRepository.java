package com.oficina.billing;
import java.util.UUID;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
interface QuoteRepository extends JpaRepository<Quote, UUID> {
    Optional<Quote> findByBranchIdAndOrderId(UUID branchId, UUID orderId);
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select q from Quote q where q.id = :id")
    Optional<Quote> findLockedById(@org.springframework.data.repository.query.Param("id") UUID id);
}
