package com.ledgercore.audit;

import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface AuditRepository extends JpaRepository<AuditEvent, Long> {

    Optional<AuditEvent> findTopByOrderBySeqDesc();

    @Query("SELECT e FROM AuditEvent e WHERE e.seq > :lastSeq ORDER BY e.seq ASC LIMIT :batchSize")
    List<AuditEvent> findNextBatch(@Param("lastSeq") long lastSeq, @Param("batchSize") int batchSize);

    @Query("""
        SELECT e FROM AuditEvent e
        WHERE (:action IS NULL OR e.action = :action)
          AND (:actorUserId IS NULL OR e.actorUserId = :actorUserId)
          AND (cast(:from as timestamp) IS NULL OR e.occurredAt >= :from)
          AND (cast(:to as timestamp) IS NULL OR e.occurredAt <= :to)
        ORDER BY e.seq DESC
    """)
    Slice<AuditEvent> findFiltered(
            @Param("action") String action,
            @Param("actorUserId") UUID actorUserId,
            @Param("from") Instant from,
            @Param("to") Instant to,
            Pageable pageable);
}
