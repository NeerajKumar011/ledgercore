package com.ledgercore.transfer;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface TransferRepository extends JpaRepository<Transfer, UUID> {

    Optional<Transfer> findByIdempotencyKey(String idempotencyKey);

    boolean existsByIdempotencyKey(String idempotencyKey);

    /**
     * Atomically claims an idempotency key by inserting a PENDING transfer row.
     * <p>Uses {@code ON CONFLICT (idempotency_key) DO NOTHING} so that if
     * another concurrent transaction already inserted the same key, this
     * statement silently does nothing and returns 0 rows inserted.
     *
     * @return 1 if the row was inserted (key claimed), 0 if the key already
     *         existed (another transaction won the race — the caller must then
     *         re-read the existing transfer by idempotency key)
     */
    @Modifying
    @Query(nativeQuery = true, value = """
            INSERT INTO transfers
                (id, idempotency_key, request_hash, from_account_id,
                 to_account_id, amount_minor, currency, status,
                 created_at, updated_at)
            VALUES
                (:id, :idempotencyKey, :requestHash, :fromAccountId,
                 :toAccountId, :amountMinor, :currency, 'PENDING',
                 :now, :now)
            ON CONFLICT (idempotency_key) DO NOTHING
            """)
    int insertPendingIfAbsent(
            @Param("id")             UUID    id,
            @Param("idempotencyKey") String  idempotencyKey,
            @Param("requestHash")    String  requestHash,
            @Param("fromAccountId")  UUID    fromAccountId,
            @Param("toAccountId")    UUID    toAccountId,
            @Param("amountMinor")    long    amountMinor,
            @Param("currency")       String  currency,
            @Param("now")            Instant now
    );
}
