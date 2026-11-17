package com.oficina.billing;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.domain.Pageable;
import jakarta.persistence.LockModeType;
interface OutboxRepository extends JpaRepository<OutboxEvent, UUID> {
    List<OutboxEvent> findByAggregateId(UUID aggregateId);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from OutboxEvent e where e.publishedAt is null order by e.occurredAt, e.eventId")
    List<OutboxEvent> lockPending(Pageable page);
}
