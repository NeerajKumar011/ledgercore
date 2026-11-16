package com.ledgercore;

import com.ledgercore.account.Account;
import com.ledgercore.account.AccountKind;
import com.ledgercore.account.AccountRepository;
import com.ledgercore.account.AccountStatus;
import com.ledgercore.ledger.EntryDirection;
import com.ledgercore.ledger.LedgerEntry;
import com.ledgercore.ledger.LedgerEntryRepository;
import com.ledgercore.transfer.Transfer;
import com.ledgercore.transfer.TransferRepository;
import com.ledgercore.transfer.TransferStatus;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class DomainIntegrationTest {

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private TransferRepository transferRepository;

    @Autowired
    private LedgerEntryRepository ledgerEntryRepository;

    @Autowired
    private EntityManager entityManager;

    @Test
    @DisplayName("Persist and reload Account with all fields")
    void shouldPersistAndReloadAccount() {
        Account account = Account.create("ACC-10001", "OWNER-A", "USD");
        account.credit(50000L);

        Account saved = accountRepository.saveAndFlush(account);
        entityManager.clear();

        Account reloaded = accountRepository.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getAccountNumber()).isEqualTo("ACC-10001");
        assertThat(reloaded.getOwnerRef()).isEqualTo("OWNER-A");
        assertThat(reloaded.getCurrency()).isEqualTo("USD");
        assertThat(reloaded.getKind()).isEqualTo(AccountKind.CUSTOMER);
        assertThat(reloaded.getStatus()).isEqualTo(AccountStatus.ACTIVE);
        assertThat(reloaded.getBalanceMinor()).isEqualTo(50000L);
        assertThat(reloaded.getVersion()).isEqualTo(0L);
        assertThat(reloaded.getCreatedAt()).isNotNull();
        assertThat(reloaded.getUpdatedAt()).isNotNull();
    }

    @Test
    @DisplayName("Persist and reload Transfer and LedgerEntry with all fields")
    void shouldPersistAndReloadTransferAndLedgerEntries() {
        Account sender = Account.create("ACC-SENDER-1", "OWNER-S", "USD");
        sender.credit(10000L);
        accountRepository.save(sender);

        Account receiver = Account.create("ACC-RECEIVER-1", "OWNER-R", "USD");
        receiver.credit(2000L);
        accountRepository.save(receiver);
        accountRepository.flush();

        Transfer transfer = Transfer.create("IDEMP-TX-100", "HASH-SHA256-XYZ",
                sender.getId(), receiver.getId(), 2500L, "USD");
        Transfer savedTransfer = transferRepository.saveAndFlush(transfer);

        LedgerEntry debitEntry = LedgerEntry.create(savedTransfer.getId(), sender.getId(),
                EntryDirection.DEBIT, 2500L, "USD");
        LedgerEntry creditEntry = LedgerEntry.create(savedTransfer.getId(), receiver.getId(),
                EntryDirection.CREDIT, 2500L, "USD");

        ledgerEntryRepository.save(debitEntry);
        ledgerEntryRepository.save(creditEntry);
        ledgerEntryRepository.flush();

        entityManager.clear();

        // Verify Transfer
        Transfer reloadedTx = transferRepository.findByIdempotencyKey("IDEMP-TX-100").orElseThrow();
        assertThat(reloadedTx.getId()).isEqualTo(savedTransfer.getId());
        assertThat(reloadedTx.getRequestHash()).isEqualTo("HASH-SHA256-XYZ");
        assertThat(reloadedTx.getFromAccountId()).isEqualTo(sender.getId());
        assertThat(reloadedTx.getToAccountId()).isEqualTo(receiver.getId());
        assertThat(reloadedTx.getAmountMinor()).isEqualTo(2500L);
        assertThat(reloadedTx.getCurrency()).isEqualTo("USD");
        assertThat(reloadedTx.getStatus()).isEqualTo(TransferStatus.PENDING);
        assertThat(reloadedTx.getCreatedAt()).isNotNull();
        assertThat(reloadedTx.getUpdatedAt()).isNotNull();

        // Verify LedgerEntries
        List<LedgerEntry> entriesByTx = ledgerEntryRepository.findByTransferId(reloadedTx.getId());
        assertThat(entriesByTx).hasSize(2);

        List<LedgerEntry> senderEntries = ledgerEntryRepository.findByAccountIdOrderByCreatedAtAsc(sender.getId());
        assertThat(senderEntries).hasSize(1);
        assertThat(senderEntries.getFirst().getDirection()).isEqualTo(EntryDirection.DEBIT);
        assertThat(senderEntries.getFirst().getAmountMinor()).isEqualTo(2500L);
        assertThat(senderEntries.getFirst().getCurrency()).isEqualTo("USD");
    }

    @Test
    @DisplayName("Duplicate account_number is rejected by UNIQUE constraint")
    void shouldRejectDuplicateAccountNumber() {
        Account first = Account.create("ACC-DUP-1", "OWNER-1", "USD");
        first.credit(1000L);
        accountRepository.saveAndFlush(first);

        Account second = Account.create("ACC-DUP-1", "OWNER-2", "USD");
        second.credit(2000L);

        assertThatThrownBy(() -> accountRepository.saveAndFlush(second))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("Duplicate idempotency_key is rejected by UNIQUE constraint")
    void shouldRejectDuplicateIdempotencyKey() {
        Account a1 = Account.create("ACC-TX-1", "O1", "USD");
        a1.credit(5000L);
        accountRepository.save(a1);

        Account a2 = Account.create("ACC-TX-2", "O2", "USD");
        a2.credit(5000L);
        accountRepository.save(a2);
        accountRepository.flush();

        Transfer tx1 = Transfer.create("IDEMP-DUP-KEY", "HASH-1", a1.getId(), a2.getId(), 500L, "USD");
        transferRepository.saveAndFlush(tx1);

        Transfer tx2 = Transfer.create("IDEMP-DUP-KEY", "HASH-2", a1.getId(), a2.getId(), 500L, "USD");

        assertThatThrownBy(() -> transferRepository.saveAndFlush(tx2))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("DB CHECK constraint rejects negative customer account balance")
    void shouldRejectNegativeAccountBalance() {
        UUID accountId = UUID.randomUUID();
        Instant now = Instant.now();

        assertThatThrownBy(() -> {
            entityManager.createNativeQuery("""
                    INSERT INTO accounts (id, account_number, owner_ref, currency, status, balance_minor, version, created_at, updated_at, kind)
                    VALUES (:id, 'ACC-NEGATIVE', 'OWNER-NEG', 'USD', 'ACTIVE', -100, 0, :now, :now, 'CUSTOMER')
                    """)
                    .setParameter("id", accountId)
                    .setParameter("now", now)
                    .executeUpdate();
            entityManager.flush();
        }).isInstanceOf(PersistenceException.class);
    }

    @Test
    @DisplayName("DB CHECK constraint rejects transfer with zero amount")
    void shouldRejectZeroAmountTransfer() {
        Account a1 = Account.create("ACC-Z-1", "O1", "USD");
        a1.credit(5000L);
        accountRepository.save(a1);

        Account a2 = Account.create("ACC-Z-2", "O2", "USD");
        a2.credit(5000L);
        accountRepository.save(a2);
        accountRepository.flush();

        UUID txId = UUID.randomUUID();
        Instant now = Instant.now();

        assertThatThrownBy(() -> {
            entityManager.createNativeQuery("""
                    INSERT INTO transfers (id, idempotency_key, request_hash, from_account_id, to_account_id, amount_minor, currency, status, created_at, updated_at)
                    VALUES (:id, 'IDEMP-ZERO-AMT', 'HASH-Z', :fromId, :toId, 0, 'USD', 'PENDING', :now, :now)
                    """)
                    .setParameter("id", txId)
                    .setParameter("fromId", a1.getId())
                    .setParameter("toId", a2.getId())
                    .setParameter("now", now)
                    .executeUpdate();
            entityManager.flush();
        }).isInstanceOf(PersistenceException.class);
    }

    @Test
    @DisplayName("DB CHECK constraint rejects ledger entry with zero amount")
    void shouldRejectZeroAmountLedgerEntry() {
        Account a1 = Account.create("ACC-ZL-1", "O1", "USD");
        a1.credit(5000L);
        accountRepository.save(a1);

        Account a2 = Account.create("ACC-ZL-2", "O2", "USD");
        a2.credit(5000L);
        accountRepository.save(a2);
        accountRepository.flush();

        Transfer tx = transferRepository.saveAndFlush(
                Transfer.create("IDEMP-ZL-TX", "HASH-ZL", a1.getId(), a2.getId(), 100L, "USD")
        );

        UUID entryId = UUID.randomUUID();
        Instant now = Instant.now();

        assertThatThrownBy(() -> {
            entityManager.createNativeQuery("""
                    INSERT INTO ledger_entries (id, transfer_id, account_id, direction, amount_minor, currency, created_at)
                    VALUES (:id, :txId, :accId, 'DEBIT', 0, 'USD', :now)
                    """)
                    .setParameter("id", entryId)
                    .setParameter("txId", tx.getId())
                    .setParameter("accId", a1.getId())
                    .setParameter("now", now)
                    .executeUpdate();
            entityManager.flush();
        }).isInstanceOf(PersistenceException.class);
    }

    @Test
    @DisplayName("ledger_entries UPDATE fails via database trigger")
    void shouldFailWhenUpdatingLedgerEntryViaTrigger() {
        Account a1 = Account.create("ACC-TRG-1", "O1", "USD");
        a1.credit(5000L);
        accountRepository.save(a1);

        Account a2 = Account.create("ACC-TRG-2", "O2", "USD");
        a2.credit(5000L);
        accountRepository.save(a2);
        accountRepository.flush();

        Transfer tx = transferRepository.saveAndFlush(
                Transfer.create("IDEMP-TRG-TX", "HASH-TRG", a1.getId(), a2.getId(), 500L, "USD")
        );

        LedgerEntry entry = ledgerEntryRepository.saveAndFlush(
                LedgerEntry.create(tx.getId(), a1.getId(), EntryDirection.DEBIT, 500L, "USD")
        );
        ledgerEntryRepository.saveAndFlush(
                LedgerEntry.create(tx.getId(), a2.getId(), EntryDirection.CREDIT, 500L, "USD")
        );

        assertThatThrownBy(() -> {
            entityManager.createNativeQuery("UPDATE ledger_entries SET amount_minor = 9999 WHERE id = :id")
                    .setParameter("id", entry.getId())
                    .executeUpdate();
            entityManager.flush();
        })
                .isInstanceOf(PersistenceException.class)
                .hasMessageContaining("ledger_entries is INSERT-only: UPDATE on row id=");
    }

    @Test
    @DisplayName("ledger_entries DELETE fails via database trigger")
    void shouldFailWhenDeletingLedgerEntryViaTrigger() {
        Account a1 = Account.create("ACC-TRG-DEL-1", "O1", "USD");
        a1.credit(5000L);
        accountRepository.save(a1);

        Account a2 = Account.create("ACC-TRG-DEL-2", "O2", "USD");
        a2.credit(5000L);
        accountRepository.save(a2);
        accountRepository.flush();

        Transfer tx = transferRepository.saveAndFlush(
                Transfer.create("IDEMP-TRG-DEL", "HASH-DEL", a1.getId(), a2.getId(), 500L, "USD")
        );

        LedgerEntry entry = ledgerEntryRepository.saveAndFlush(
                LedgerEntry.create(tx.getId(), a1.getId(), EntryDirection.DEBIT, 500L, "USD")
        );
        ledgerEntryRepository.saveAndFlush(
                LedgerEntry.create(tx.getId(), a2.getId(), EntryDirection.CREDIT, 500L, "USD")
        );

        assertThatThrownBy(() -> {
            entityManager.createNativeQuery("DELETE FROM ledger_entries WHERE id = :id")
                    .setParameter("id", entry.getId())
                    .executeUpdate();
            entityManager.flush();
        })
                .isInstanceOf(PersistenceException.class)
                .hasMessageContaining("ledger_entries is INSERT-only: DELETE on row id=");
    }

    @Test
    @DisplayName("Account version increments on update")
    void shouldIncrementAccountVersionOnUpdate() {
        Account account = Account.create("ACC-VER-1", "OWNER-V", "USD");
        account.credit(10000L);
        Account saved = accountRepository.saveAndFlush(account);
        assertThat(saved.getVersion()).isEqualTo(0L);

        saved.credit(5000L);
        Account updated = accountRepository.saveAndFlush(saved);
        entityManager.clear();

        Account reloaded = accountRepository.findById(updated.getId()).orElseThrow();
        assertThat(reloaded.getVersion()).isEqualTo(1L);
        assertThat(reloaded.getBalanceMinor()).isEqualTo(15000L);
    }
}
