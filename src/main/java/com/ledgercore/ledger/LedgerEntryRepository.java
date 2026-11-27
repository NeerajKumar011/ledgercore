package com.ledgercore.ledger;

import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface LedgerEntryRepository extends JpaRepository<LedgerEntry, UUID> {

    List<LedgerEntry> findByTransferId(UUID transferId);

    List<LedgerEntry> findByAccountIdOrderByCreatedAtAsc(UUID accountId);

    @Query("SELECT e FROM LedgerEntry e WHERE e.accountId = :accountId ORDER BY e.createdAt DESC, e.id DESC")
    Slice<LedgerEntry> findByAccountIdOrderByCreatedAtDescIdDesc(@Param("accountId") UUID accountId, Pageable pageable);
}
