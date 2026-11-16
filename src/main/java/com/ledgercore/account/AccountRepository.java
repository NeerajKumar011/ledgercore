package com.ledgercore.account;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface AccountRepository extends JpaRepository<Account, UUID> {

    Optional<Account> findByAccountNumber(String accountNumber);

    boolean existsByAccountNumber(String accountNumber);

    Optional<Account> findByKindAndCurrency(AccountKind kind, String currency);

    @Query("SELECT a.currency FROM Account a WHERE a.id = :id")
    Optional<String> findCurrencyById(@Param("id") UUID id);

    @Query("SELECT a.id FROM Account a WHERE a.accountNumber = :accountNumber")
    Optional<UUID> findIdByAccountNumber(@Param("accountNumber") String accountNumber);

    /**
     * Locks a single account row with {@code SELECT ... FOR UPDATE}.
     * <p>Must be called within an active transaction. Returns empty if the
     * account does not exist — callers must throw
     * {@code AccountNotFoundException} in that case.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM Account a WHERE a.id = :id")
    Optional<Account> findByIdForUpdate(@Param("id") UUID id);

    /**
     * Atomically inserts a SYSTEM funding account row if it does not already exist.
     * <p>Uses {@code ON CONFLICT (account_number) DO NOTHING} to be concurrent-safe.
     *
     * @return number of rows inserted (1 if inserted, 0 if already existed)
     */
    @Modifying
    @Query(nativeQuery = true, value = """
            INSERT INTO accounts
                (id, account_number, owner_ref, currency, status,
                 balance_minor, version, created_at, updated_at, kind)
            VALUES
                (:id, :accountNumber, 'SYSTEM', :currency, 'ACTIVE',
                 0, 0, :now, :now, 'SYSTEM')
            ON CONFLICT (account_number) DO NOTHING
            """)
    int insertSystemFundingIfAbsent(
            @Param("id")            UUID    id,
            @Param("accountNumber") String  accountNumber,
            @Param("currency")      String  currency,
            @Param("now")           Instant now
    );
}
