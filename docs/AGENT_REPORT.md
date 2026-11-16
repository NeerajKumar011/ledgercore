# LedgerCore Agent Report

## Current status
- **Phase / step:** Phase 2, Step C — Concurrency Proof (No HTTP, No Controllers)
- **Overall:** DONE
- **Last updated:** 2026-11-16T10:40 IST
- **One-line summary:** Phase 2 Step C completed: Concurrency test suite implemented with 9 comprehensive scenarios (80 tests total across all suites); stale-entity persistence context caching risk eliminated via repository projection queries (`findCurrencyById`, `findIdByAccountNumber`) and `detach()`; configurable bounded lock wait implemented via `SELECT set_config('lock_timeout', :timeout, true)` translating SQLSTATE 55P03 to `TransferBusyException`; non-mutating row mapping in `ReconciliationService`; verified across 3 consecutive clean test runs (80 tests, 0 failures, 0 errors, 0 flaky) and Docker Compose startup.

---

## Open issues

| # | Issue | Exact error | Cause | Status |
|---|-------|-------------|-------|--------|
| 1 | Two Maven repos HTTP 401 warnings (non-fatal) | `Could not transfer metadata from flyway-community-db-support ... HTTP Status: 401` | Pre-configured repos in local Maven settings without credentials; Flyway community drivers are not needed | **NOTE** — non-fatal warnings during Maven sync |
| 2 | Host port 5432 occupied by Windows PostgreSQL service | `psql: error: connection to server at "127.0.0.1", port 5432 failed: FATAL: role "ledger_user" does not exist` | Host machine has a pre-existing native Windows PostgreSQL service (`postgresql-x64-18`) on port 5432 | **RESOLVED** — set `DB_PORT=5433` in `.env`; `docker-compose.yml` maps `${DB_PORT:-5432}:5432` and `application.properties` connects via `${DB_PORT}` |
| 3 | Host port 8080 occupied by Apache HTTP server | `Web server failed to start. Port 8080 was already in use.` | Host machine has an active `httpd` process on port 8080 | **RESOLVED** — set `server.port=8081` in `application.properties` |
| 4 | Bounded lock wait on pessimistic locking | `55P03: canceling statement due to lock timeout` | Under high contention or deadlocks, pessimistic locking would block indefinitely without a transaction-level lock timeout | **RESOLVED** — configured via `ledgercore.transfer.lock-timeout` (default `3s`), applied per-transaction via `SELECT set_config('lock_timeout', :timeout, true)`, and translated from SQLSTATE `55P03` to `TransferBusyException` |

---

## Manual actions needed from the human
None! All 80 unit and integration tests are passing and verified end-to-end.

---

## Log

### Phases 0-1 summary — Verification, Database Setup & Domain Foundation (2026-11-14)

- **Status:** COMPLETED & COMMITTED
- **Tool Versions Verified:**
  - Java: OpenJDK 25.0.4.1 (Temurin-25.0.4.1+1)
  - Compiler: javac 25.0.4.1
  - Build Tool: Apache Maven Wrapper 3.10.0
  - Git: git version 2.49.0.windows.1
  - Docker Engine: Docker 29.8.0 / 29.9.3, Docker Compose v5.5.1
  - Framework: Spring Boot 4.1.2 GA, Hibernate ORM 7.4.13.Final, PostgreSQL 18.7
- **Key Infrastructure & Port Decisions:**
  - **Docker Engine Migration to D:** Host C: drive ran out of disk space (~14MB free), causing containerd to mount read-only. Human freed 2.59GB on C: and reinstalled Docker Desktop to `D:\Docker`.
  - **Port 5433 for PostgreSQL:** Host machine has native Windows PostgreSQL 18 service (`postgresql-x64-18`) on port 5432 where role `ledger_user` does not exist. Set `DB_PORT=5433` in `.env` and mapped `${DB_PORT:-5432}:5432` in `docker-compose.yml`.
  - **Port 8081 for Spring Boot:** Host machine runs Apache `httpd` on port 8080. Added `server.port=8081` in `application.properties`.
  - **UTC Timezone Enforcement:** The PostgreSQL JDBC driver transmits the host JVM timezone to PostgreSQL during connection handshake. The host JVM reported `"Asia/Calcutta"` which PostgreSQL rejected with `FATAL: invalid value for parameter "TimeZone": "Asia/Calcutta"`. Enforced UTC everywhere: `-Duser.timezone=UTC` in `maven-surefire-plugin` `<argLine>` and `spring-boot-maven-plugin` `<jvmArguments>`, pinned `spring.jpa.properties.hibernate.jdbc.time_zone=UTC` in `application.properties`, and pinned Testcontainers image to `postgres:18`.
- **Key Architecture & Design Decisions:**
  - **Database Migrations:** Schema managed strictly via Flyway (`V1__init_schema.sql` creates `accounts`, `transfers`, and `ledger_entries` with UUID PKs, `timestamptz`, `bigint` amounts, and insert-only row trigger).
  - **CHAR(3) Currency Mapping:** In `V1__init_schema.sql`, currency is `CHAR(3)`. Standard JPA `String` maps to `VARCHAR`, causing Hibernate `ddl-auto=validate` to fail. Mapped with `@JdbcTypeCode(SqlTypes.CHAR)` and `columnDefinition = "char(3)"` in entities.
  - **Money Representation:** Money is always primitive `long` minor units (`amount_minor`), manipulated in Java via immutable `Money` record with `Math.addExact`/`subtractExact` overflow checking. Floating point is strictly forbidden.
  - **Plain UUID References in Transfer:** `Transfer` stores `fromAccountId` and `toAccountId` as plain `UUID` fields rather than JPA `@ManyToOne` entity references to avoid lazy-loading traps, cascading issues, and unexpected N+1 queries.
  - **Ledger Immutability:** `LedgerEntry` is annotated with Hibernate `@Immutable`, has no setters, and PostgreSQL database triggers prevent any `UPDATE` or `DELETE`.
- **Commit History:**
  - Commit `8cedc26`: `chore(phase-0): add agent rules, .env.example, and hygiene entries`
  - Phase 1 Step 1: `feat(db): setup postgres 18 with flyway v1 schema and immutable ledger trigger`
  - Phase 1 Step 2: `feat(domain): implement JPA entities, repositories, Money record, and test suite`

### Entry 7 — Phase 1 Steps 1 and 2 Human Commit Record — 2026-11-15 22:52 IST

- **Status:** COMMITTED by human
- **What the human verified and committed:**
  - Phase 1 Step 1 (database setup: `docker-compose.yml`, `V1__init_schema.sql`, `application.properties`, `TestcontainersConfiguration`) was committed and pushed by the human.
  - Phase 1 Step 2 (JPA entities, repositories, `Money` record, unit tests, integration tests) was committed and pushed by the human.
  - Both commits are on branch `main` and pushed to `origin/main`.
- **Next step proposed:** Phase 2, Step A — Transfer engine (service, V2 migration, hardened repositories, exceptions).

### Entry 8 — Phase 2 Step A — Transfer Engine — 2026-11-15 23:00 IST

- **Status:** DONE (all tasks and verification steps completed and passing)

- **What was done:**
  - Adhered strictly to `docs/AGENT_RULES.md`: Rule 1 (honest outputs), Rule 2 (no unauthorized dependencies), Rule 4 (Money as BIGINT minor units), Rule 6 (no tests disabled/deleted), Rule 9 (Hibernate 7 / Spring Boot 4 APIs).
  - Maintained Flyway schema immutability: `V1__init_schema.sql` was not touched (confirmed empty git diff). Created `src/main/resources/db/migration/V2__ledger_hardening.sql`:
    - Statement-level trigger `ledger_entries_no_truncate` (`BEFORE TRUNCATE ON ledger_entries FOR EACH STATEMENT`) calling `ledger_entries_no_truncate()` which aborts TRUNCATE with `'ledger_entries is INSERT-only: TRUNCATE is forbidden'`.
    - Added ISO-4217 currency regex check constraints `CHECK (currency ~ '^[A-Z]{3}$')` on `accounts`, `transfers`, and `ledger_entries`.
    - Added failure reason consistency check `CHECK ((status = 'FAILED') = (failure_reason IS NOT NULL))` on `transfers`.
    - Added performance indexes: `transfers_from_account_id_idx` on `transfers(from_account_id)` and `transfers_to_account_id_idx` on `transfers(to_account_id)`.
  - Tightened `Account` entity domain state machine:
    - Removed generic `changeStatus()` method.
    - Added explicit lifecycle methods: `freeze()` (ACTIVE -> FROZEN), `close()` (ACTIVE/FROZEN -> CLOSED), and `reactivate()` (FROZEN -> ACTIVE; throws if CLOSED).
  - Optimized `LedgerEntry` insertion:
    - Implemented `Persistable<UUID>` with `@Transient private boolean isNew = true` set on creation. This guides Spring Data JPA to invoke `EntityManager.persist()` instead of `merge()`, guaranteeing a pure `INSERT` without a preceding `SELECT`.
  - Hardened Repositories:
    - Added `AccountRepository.findByIdForUpdate(UUID)` annotated with `@Lock(LockModeType.PESSIMISTIC_WRITE)` for ordered single-row locking.
    - Added `TransferRepository.insertPendingIfAbsent(...)` executing native atomic `INSERT ... ON CONFLICT (idempotency_key) DO NOTHING` returning the rows affected count.
  - Implemented `com.ledgercore.transfer`:
    - `TransferCommand`: immutable record containing `idempotencyKey`, `fromAccountId`, `toAccountId`, `amountMinor`, and `currency`.
    - `TransferResult`: immutable record containing `transferId`, `status`, `failureReason`, and `replayed`.
    - Unchecked domain exceptions: `InvalidTransferRequestException`, `AccountNotFoundException`, and `IdempotencyConflictException`.
    - `TransferService`: implements the exact 7-step transactional execution flow:
      1. Structural validation (non-blank/length <= 128 key, amount > 0, from != to, currency regex, non-null fields).
      2. SHA-256 request hash calculation of canonical `v1|from|to|amount|currency`.
      3. Idempotency pre-check by key: returns stored result with `replayed=true` on matching hash (even if FAILED); throws `IdempotencyConflictException` on mismatch.
      4. Pessimistic locking of both accounts via `findByIdForUpdate` in ascending UUID order to prevent deadlocks.
      5. Atomic reservation of idempotency key via `insertPendingIfAbsent`. If 0 rows affected, re-reads and delegates to step 3.
      6. Business rules evaluation (both accounts ACTIVE, matching currency, sufficient balance). On rule failure, records `TransferStatus.FAILED` with reason, commits normally, and returns result without throwing.
      7. Fund movement: debits source, credits destination using `Math.subtractExact` / `Math.addExact`, inserts two immutable `LedgerEntry` rows, marks transfer `COMPLETED`, returns `TransferResult(..., COMPLETED, null, false)`.
  - Created test suites:
    - `TransferServiceUnitTest`: 17 pure unit tests verifying SHA-256 determinism, sensitivity to each field, and comprehensive validation failure checks.
    - `TransferServiceIntegrationTest`: 11 integration tests on PostgreSQL 18 via Testcontainers without test-level `@Transactional` to ensure real commit semantics:
      - Happy path transfer with money conservation and 2 ledger entries.
      - Insufficient funds leading to FAILED status.
      - Frozen and closed account transfers failing gracefully.
      - Currency mismatch failing gracefully.
      - Idempotent replay of COMPLETED transfers without balance re-application.
      - Idempotency conflict throwing `IdempotencyConflictException`.
      - FAILED transfer replay remaining FAILED even after account is subsequently funded.
      - Non-existent account throwing `AccountNotFoundException` with zero transfer records stored.
      - Self-transfer rejecting with `InvalidTransferRequestException`.
      - Database trigger blocking `TRUNCATE ledger_entries`.
    - Fixed visibility of `TestcontainersConfiguration` from package-private to `public` to allow access from `com.ledgercore.transfer` package tests.

- **Files created/changed (with explanation):**

  | File | Action | Explanation |
  |------|--------|-------------|
  | `src/main/resources/db/migration/V2__ledger_hardening.sql` | Created | Flyway V2 migration adding TRUNCATE trigger on ledger_entries, regex checks on currency, failure_reason check on transfers, and indexes on transfer account IDs. |
  | `src/main/java/com/ledgercore/account/Account.java` | Modified | Tightened state transitions: removed changeStatus(), added freeze(), close(), and reactivate(). |
  | `src/main/java/com/ledgercore/ledger/LedgerEntry.java` | Modified | Implemented Persistable<UUID> with transient isNew flag to force pure INSERTs via persist() without SELECT-before-insert. |
  | `src/main/java/com/ledgercore/account/AccountRepository.java` | Modified | Added findByIdForUpdate(UUID) with @Lock(PESSIMISTIC_WRITE). |
  | `src/main/java/com/ledgercore/transfer/TransferRepository.java` | Modified | Added insertPendingIfAbsent native query with ON CONFLICT DO NOTHING. |
  | `src/main/java/com/ledgercore/transfer/TransferCommand.java` | Created | Immutable record representing transfer execution input. |
  | `src/main/java/com/ledgercore/transfer/TransferResult.java` | Created | Immutable record representing transfer execution output. |
  | `src/main/java/com/ledgercore/transfer/InvalidTransferRequestException.java` | Created | Unchecked exception thrown on input validation failure. |
  | `src/main/java/com/ledgercore/transfer/AccountNotFoundException.java` | Created | Unchecked exception thrown when an account does not exist. |
  | `src/main/java/com/ledgercore/transfer/IdempotencyConflictException.java` | Created | Unchecked exception thrown when an idempotency key is reused with a different payload. |
  | `src/main/java/com/ledgercore/transfer/TransferService.java` | Created | Core transfer engine executing 7-step transactional transfer flow. |
  | `src/test/java/com/ledgercore/TestcontainersConfiguration.java` | Modified | Changed class visibility to public so subpackages can import it. |
  | `src/test/java/com/ledgercore/transfer/TransferServiceUnitTest.java` | Created | 17 unit tests verifying request-hash determinism/sensitivity and input validation. |
  | `src/test/java/com/ledgercore/transfer/TransferServiceIntegrationTest.java` | Created | 11 integration tests on PostgreSQL 18 via Testcontainers verifying all business rules, idempotency replay/conflict, and TRUNCATE trigger. |
  | `docs/AGENT_REPORT.md` | Modified | Recorded Phase 2 Step A verification, updated Current status, Open issues, and Log. |

- **Commands run and results (key lines):**

  ```
  1. mvnw.cmd test (without JAVA_TOOL_OPTIONS):
    [INFO] Running com.ledgercore.common.MoneyTest
    [INFO] Tests run: 8, Failures: 0, Errors: 0, Skipped: 0
    [INFO] Running com.ledgercore.DomainIntegrationTest
    [INFO] Tests run: 10, Failures: 0, Errors: 0, Skipped: 0
    [INFO] Running com.ledgercore.LedgercoreApplicationTests
    [INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0
    [INFO] Running com.ledgercore.transfer.TransferServiceIntegrationTest
    2026-11-15T17:29:02.118Z  WARN 15368 --- [ledgercore] [main] org.hibernate.orm.jdbc.error : ERROR: ledger_entries is INSERT-only: TRUNCATE is forbidden
    [INFO] Tests run: 11, Failures: 0, Errors: 0, Skipped: 0
    [INFO] Running com.ledgercore.transfer.TransferServiceUnitTest
    [INFO] Tests run: 17, Failures: 0, Errors: 0, Skipped: 0
    [INFO] Results:
    [INFO] Tests run: 47, Failures: 0, Errors: 0, Skipped: 0
    [INFO] BUILD SUCCESS (35.967 s)

  2. docker compose up -d
    Container ledgercore-db Started (healthy on port 5433)

  3. mvnw.cmd spring-boot:run
    2026-11-15T17:29:41.228Z INFO 4396 --- [ledgercore] [main] org.flywaydb.core.FlywayExecutor : Database: jdbc:postgresql://localhost:5433/ledgercore (PostgreSQL 18.7)
    2026-11-15T17:29:41.391Z INFO 4396 --- [ledgercore] [main] o.f.core.internal.command.DbValidate : Successfully validated 2 migrations
    2026-11-15T17:29:41.428Z INFO 4396 --- [ledgercore] [main] o.f.core.internal.command.DbMigrate : Current version of schema "public": 1
    2026-11-15T17:29:41.457Z INFO 4396 --- [ledgercore] [main] o.f.core.internal.command.DbMigrate : Migrating schema "public" to version "2 - ledger hardening"
    2026-11-15T17:29:41.552Z INFO 4396 --- [ledgercore] [main] o.f.core.internal.command.DbMigrate : Successfully applied 1 migration to schema "public", now at version v2
    2026-11-15T17:29:43.367Z INFO 4396 --- [ledgercore] [main] j.LocalContainerEntityManagerFactoryBean : Initialized JPA EntityManagerFactory for persistence unit 'default'
    2026-11-15T17:29:44.791Z INFO 4396 --- [ledgercore] [main] com.ledgercore.LedgercoreApplication : Started LedgercoreApplication in 7.038 seconds

  4. git diff src/main/resources/db/migration/V1__init_schema.sql
    (empty — 0 bytes modified)

  5. docker compose stop
    Container ledgercore-db Stopped
  ```

- **Decisions made and why:**
  - **`Persistable<UUID>` on `LedgerEntry`**: Because entity IDs are client-generated UUIDs, Spring Data JPA's `SimpleJpaRepository.save()` by default considers the entity existing and calls `EntityManager.merge()`, which causes an unwanted `SELECT` query prior to insert. Implementing `Persistable<UUID>` with a transient `isNew=true` flag signals Spring Data to call `EntityManager.persist()` directly, ensuring pure `INSERT` execution.
  - **Pessimistic locking in ascending UUID order**: Locking accounts one by one ordered by `UUID.compareTo()` prevents circular wait deadlocks when concurrent transfers transfer money between the same two accounts in opposite directions.
  - **Single public `@Transactional` boundary**: All steps 1–7 of `TransferService.execute` occur within a single transaction. Business rule rejections mark the transfer `FAILED` and commit the audit record, returning the outcome without throwing, while system/DB errors bubble up to trigger standard rollbacks.

- **How to verify this yourself:**
  1. `.\mvnw.cmd test` — runs all 47 tests against Testcontainers PostgreSQL 18; all pass.
  2. `git diff src/main/resources/db/migration/V1__init_schema.sql` — confirms V1 was never touched.
  3. `docker compose up -d` followed by `.\mvnw.cmd spring-boot:run` — verifies V2 migration applies on PostgreSQL 18 and schema validates.
  4. `docker compose stop` — cleanly stops Compose container.

- **Suggested commit message:**
  ```
  feat(transfer): implement transfer engine with pessimistic locking, idempotency, and V2 hardening

  - V2__ledger_hardening.sql: TRUNCATE trigger, currency regex check, failure_reason check, account id indexes
  - Account: tighten state transitions with freeze(), close(), reactivate()
  - LedgerEntry: implement Persistable<UUID> for pure zero-SELECT inserts
  - AccountRepository: add findByIdForUpdate with PESSIMISTIC_WRITE lock
  - TransferRepository: add native insertPendingIfAbsent with ON CONFLICT DO NOTHING
  - TransferService: complete 7-step transactional execution flow with SHA-256 idempotency
  - Test suites: 17 unit tests and 11 integration tests (47 tests total passing)
  - docs/AGENT_REPORT.md: update status, open issues, and Phase 2 Step A log
  ```

- **Next step proposed:**
  Phase 2, Step B: Bookkeeping integrity, system funding accounts, reconciliation service, and double-entry deferred triggers.

### Entry 9 — Phase 2 Step B — Bookkeeping Integrity — 2026-11-15 23:20 IST

- **Status:** DONE (all tasks and verification steps completed and passing)

- **What was done:**
  - Adhered strictly to `docs/AGENT_RULES.md`: Rule 1 (honest outputs), Rule 2 (no unauthorized dependencies), Rule 4 (Money as BIGINT minor units), Rule 6 (no tests disabled/deleted), Rule 9 (Hibernate 7 / Spring Boot 4 APIs).
  - Maintained Flyway schema immutability: `V1__init_schema.sql` and `V2__ledger_hardening.sql` were not touched (confirmed empty git diff). Created `src/main/resources/db/migration/V3__system_accounts_and_journal_balance.sql`:
    - Added `kind VARCHAR(10) NOT NULL DEFAULT 'CUSTOMER'` to `accounts` with `CHECK (kind IN ('CUSTOMER', 'SYSTEM'))`.
    - Relaxed `accounts_balance_non_negative_chk` to `CHECK (kind = 'SYSTEM' OR balance_minor >= 0)` keeping the exact constraint name.
    - Added partial unique index `accounts_system_currency_uidx` on `accounts(currency) WHERE (kind = 'SYSTEM')`.
    - Added `DEFERRABLE INITIALLY DEFERRED` constraint trigger `ledger_entries_journal_balanced_chk` on `ledger_entries` (AFTER INSERT, FOR EACH ROW) executing `ledger_entries_check_journal_balance()`, verifying at commit that each `transfer_id` has exactly 2 entries (1 DEBIT and 1 CREDIT, equal amounts and currencies).
  - Domain additions and refactoring:
    - Added `AccountKind` enum (`CUSTOMER`, `SYSTEM`).
    - Added `InsufficientFundsException` unchecked exception.
    - Updated `Account`:
      - `create(...)` always starts at balance 0 (removed initial balance parameter from public factory methods).
      - Added `createSystem(currency)` with account number `"SYSTEM-FUNDING-" + currency`, `ownerRef = "SYSTEM"`, `kind = AccountKind.SYSTEM`, and balance 0.
      - Removed `updateBalance(long)`.
      - Added domain methods `debit(long)` and `credit(long)` enforcing strictly positive amounts, ACTIVE status check, `InsufficientFundsException` on customer accounts with insufficient funds, and permitting negative balances on system accounts using `Math.subtractExact`/`Math.addExact`.
      - Enforced ISO-4217 currency regex validation `^[A-Z]{3}$` in `Account` and `LedgerEntry` factory methods.
    - Updated `LedgerEntry`:
      - Added `@PostPersist` and `@PostLoad` callbacks to set `isNew = false`.
      - Updated javadocs explaining `Persistable<UUID>` lifecycle and pure `INSERT` execution.
  - TransferService enhancements:
    - Updated balance mutations to use `Account.debit` and `credit`.
    - Enforced that public `execute(TransferCommand)` only permits `CUSTOMER` accounts; rejects `SYSTEM` accounts with `TransferStatus.FAILED`.
    - Added `executeFunding(TransferCommand)` requiring source account to be `SYSTEM` funding account and destination `CUSTOMER` account.
    - Consolidated execution into a single unified private transactional pipeline.
  - Implemented `AccountService` (@Service, @Transactional):
    - `getOrCreateFundingAccount(currency)`: race-safe lookup and atomic creation via native `insertSystemFundingIfAbsent` (`ON CONFLICT (account_number) DO NOTHING`).
    - `openAccount(ownerRef, currency, initialDepositMinor)`: creates customer account at 0 balance; if deposit > 0, executes funding deposit.
    - `deposit(accountId, amountMinor, idempotencyKey)`: executes funding transfer from system account via `executeFunding`.
  - Implemented `ReconciliationService` (@Service, @Transactional(readOnly = true, isolation = REPEATABLE_READ)):
    - Generates `ReconciliationReport(boolean consistent, List<String> problems, Map<String, Long> balancesByCurrency)` using SQL aggregates (`JdbcClient`):
      1. Per account: verifies `balance_minor = SUM(CREDIT) - SUM(DEBIT)` over its entries (0 if no entries).
      2. Per currency: verifies `SUM(balance_minor)` across all accounts equals 0.
      3. Per transfer: verifies COMPLETED transfers have exactly 2 valid matching entries (1 DEBIT, 1 CREDIT); FAILED transfers have 0 entries.
      4. Verifies no PENDING transfers exist in committed data.
  - Test suites refactored and expanded:
    - Created `AccountUnitTest`: 13 pure JUnit 5 unit tests verifying debit/credit rules, overflow/underflow, inactive state rejection, customer insufficient funds, system negative balance, and currency regex.
    - Refactored `DomainIntegrationTest` and `TransferServiceIntegrationTest` to use `Account.create(...)` at 0 balance and `AccountService.openAccount`/`deposit`. Replaced `findAll()` in idempotency conflict test with `findByIdempotencyKey`.
    - Added 6 new integration tests in `TransferServiceIntegrationTest`:
      - `openAccountWithDeposit`: verifies customer balance = deposit, funding account = -deposit, 2 ledger entries, reconciliation consistent.
      - `customerTransfersKeepReconciliationConsistent`: verifies transfers preserve zero-sum ledger balance and reconciliation.
      - `publicExecuteRejectsSystemAccount`: verifies public execute rejects system accounts with FAILED status.
      - `dbRefusesUnbalancedJournalAtCommit`: verifies deferred constraint trigger blocks commit when only 1 lone ledger entry is inserted.
      - `reconciliationDetectsCorruption`: verifies reconciliation detects balance tampering via SQL and recovers when restored.
      - `concurrentFundingAccountCreation`: verifies 8 concurrent threads requesting system funding account create exactly 1 account.

- **Files created/changed (with explanation):**

  | File | Action | Explanation |
  |------|--------|-------------|
  | `src/main/resources/db/migration/V3__system_accounts_and_journal_balance.sql` | Created | Flyway V3 migration: adds account kind, relaxes balance constraint for SYSTEM accounts, adds partial unique index on system currency, and creates deferred constraint trigger checking balanced journal entries. |
  | `src/main/java/com/ledgercore/account/AccountKind.java` | Created | Enum defining account kinds (`CUSTOMER`, `SYSTEM`). |
  | `src/main/java/com/ledgercore/account/InsufficientFundsException.java` | Created | Unchecked exception thrown when debiting a customer account beyond its balance. |
  | `src/main/java/com/ledgercore/account/Account.java` | Modified | Added kind, removed initial balance from create(), added createSystem(), removed updateBalance(), added debit()/credit(), added currency regex. |
  | `src/main/java/com/ledgercore/ledger/LedgerEntry.java` | Modified | Added @PostPersist and @PostLoad markNotNew() callbacks, added currency regex validation. |
  | `src/main/java/com/ledgercore/account/AccountRepository.java` | Modified | Added findByKindAndCurrency and native insertSystemFundingIfAbsent query. |
  | `src/main/java/com/ledgercore/transfer/TransferService.java` | Modified | Uses Account.debit/credit, restricts public execute to CUSTOMER accounts, adds executeFunding, shares single private pipeline. |
  | `src/main/java/com/ledgercore/account/AccountService.java` | Created | Service managing customer account opening, deposit funding, and thread-safe system funding account provisioning. |
  | `src/main/java/com/ledgercore/reconciliation/ReconciliationReport.java` | Created | Immutable record representing reconciliation outcome and discrepancies. |
  | `src/main/java/com/ledgercore/reconciliation/ReconciliationService.java` | Created | Service performing REPEATABLE_READ SQL aggregate consistency checks across accounts, currencies, and transfers. |
  | `src/test/java/com/ledgercore/account/AccountUnitTest.java` | Created | 13 unit tests for Account domain rules, overdrafts, and state machine. |
  | `src/test/java/com/ledgercore/DomainIntegrationTest.java` | Modified | Updated to use Account.create without initial balance and credit() method. |
  | `src/test/java/com/ledgercore/transfer/TransferServiceIntegrationTest.java` | Modified | Refactored existing tests to use AccountService; added 6 new integration tests for funding, reconciliation, deferred trigger, and concurrency. |
  | `docs/AGENT_REPORT.md` | Modified | Condensed Entries 1-6 into Phases 0-1 summary, updated Current status, and appended this Entry 9. |

- **Commands run and results (key lines):**

  ```
  1. mvnw.cmd test (without JAVA_TOOL_OPTIONS):
    [INFO] Running com.ledgercore.account.AccountUnitTest
    [INFO] Tests run: 13, Failures: 0, Errors: 0, Skipped: 0
    [INFO] Running com.ledgercore.common.MoneyTest
    [INFO] Tests run: 8, Failures: 0, Errors: 0, Skipped: 0
    [INFO] Running com.ledgercore.DomainIntegrationTest
    [INFO] Tests run: 10, Failures: 0, Errors: 0, Skipped: 0
    [INFO] Running com.ledgercore.LedgercoreApplicationTests
    [INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0
    [INFO] Running com.ledgercore.transfer.TransferServiceIntegrationTest
    2026-11-15T17:48:44.339Z  WARN 21616 --- [ledgercore] [main] org.hibernate.orm.jdbc.error : ERROR: ledger_entries is INSERT-only: TRUNCATE is forbidden
    [INFO] Tests run: 17, Failures: 0, Errors: 0, Skipped: 0
    [INFO] Running com.ledgercore.transfer.TransferServiceUnitTest
    [INFO] Tests run: 17, Failures: 0, Errors: 0, Skipped: 0
    [INFO] Results:
    [INFO] Tests run: 66, Failures: 0, Errors: 0, Skipped: 0
    [INFO] BUILD SUCCESS (38.665 s)

  2. docker compose up -d
    Container ledgercore-db Started (healthy on port 5433)

  3. mvnw.cmd spring-boot:run
    2026-11-15T17:49:24.460Z INFO 9704 --- [ledgercore] [main] org.flywaydb.core.FlywayExecutor : Database: jdbc:postgresql://localhost:5433/ledgercore (PostgreSQL 18.7)
    2026-11-15T17:49:24.582Z INFO 9704 --- [ledgercore] [main] o.f.core.internal.command.DbValidate : Successfully validated 3 migrations
    2026-11-15T17:49:24.614Z INFO 9704 --- [ledgercore] [main] o.f.core.internal.command.DbMigrate : Current version of schema "public": 2
    2026-11-15T17:49:24.636Z INFO 9704 --- [ledgercore] [main] o.f.core.internal.command.DbMigrate : Migrating schema "public" to version "3 - system accounts and journal balance"
    2026-11-15T17:49:24.722Z INFO 9704 --- [ledgercore] [main] o.f.core.internal.command.DbMigrate : Successfully applied 1 migration to schema "public", now at version v3
    2026-11-15T17:49:26.505Z INFO 9704 --- [ledgercore] [main] j.LocalContainerEntityManagerFactoryBean : Initialized JPA EntityManagerFactory for persistence unit 'default'
    2026-11-15T17:49:27.950Z INFO 9704 --- [ledgercore] [main] com.ledgercore.LedgercoreApplication : Started LedgercoreApplication in 6.646 seconds

  4. git diff src/main/resources/db/migration/V1__init_schema.sql
    (empty — 0 bytes modified)

  5. git diff src/main/resources/db/migration/V2__ledger_hardening.sql
    (empty — 0 bytes modified)

  6. docker compose stop
    Container ledgercore-db Stopped
  ```

- **Decisions made and why:**
  - **Deferred constraint trigger for double-entry balance**: PostgreSQL table triggers can be declared as `CONSTRAINT TRIGGER ... DEFERRABLE INITIALLY DEFERRED`. In standard transactional application code, debit and credit ledger rows are inserted sequentially. An immediate row-level trigger would fail on the first entry before the second entry is inserted. Making the trigger deferred ensures that consistency is checked at transaction commit time: the transaction must contain both entries or commit is aborted.
  - **System accounts as balancing source**: To maintain mathematical zero-sum integrity across all accounts per currency (`SUM(balance_minor) = 0`), funds injected into the system originate from currency-specific system funding accounts (`SYSTEM-FUNDING-<CCY>`). When a customer deposits money, the system account balance becomes negative, precisely offsetting the positive customer balance.
  - **SQL aggregates in ReconciliationService**: Instead of loading thousands of accounts or transfers into JVM memory, `ReconciliationService` delegates aggregate calculations to PostgreSQL using `JdbcClient` and `GROUP BY ... HAVING` clauses, ensuring fast, memory-safe integrity audits even at high scale.

- **How to verify this yourself:**
  1. `.\mvnw.cmd test` — runs all 66 unit and integration tests against PostgreSQL 18 via Testcontainers; all pass.
  2. `git diff src/main/resources/db/migration/V1__init_schema.sql` and `git diff src/main/resources/db/migration/V2__ledger_hardening.sql` — confirm V1 and V2 are untouched.
  3. `docker compose up -d` followed by `.\mvnw.cmd spring-boot:run` — verifies V3 migration applies on Compose PostgreSQL 18 and schema validates.
  4. `docker compose stop` — cleanly stops Compose container.

- **Suggested commit message:**
  ```
  feat(bookkeeping): implement system accounts, double-entry deferred trigger, and reconciliation service

  - V3__system_accounts_and_journal_balance.sql: account kind, system balance check, deferred journal balance trigger
  - Account: add AccountKind, debit(), credit(), InsufficientFundsException, createSystem()
  - LedgerEntry: add @PostPersist/@PostLoad callbacks, currency regex validation
  - AccountService: openAccount with funding deposit, race-safe getOrCreateFundingAccount
  - TransferService: add executeFunding, enforce CUSTOMER-only public execute, share private pipeline
  - ReconciliationService: REPEATABLE_READ SQL aggregate integrity audit across accounts, currencies, transfers
  - Test suites: 13 AccountUnitTests, 17 TransferServiceIntegrationTests (66 tests total passing)
  - docs/AGENT_REPORT.md: condense summary and document Phase 2 Step B verification
  ```

- **Next step proposed:**
  Phase 2, Step C: Concurrency proof, stale-entity risk elimination in AccountService, configurable bounded lock wait, and TransferConcurrencyIntegrationTest suite.

### Entry 10 — Phase 2 Steps A and B Human Commit Record — 2026-11-15 23:40 IST

- **Status:** COMMITTED by human
- **What the human verified and committed:**
  - Phase 2 Step A (Transfer engine: pessimistic locking, idempotency claim, V2 hardening migration, transfer service, unit & integration tests) was committed and pushed by the human.
  - Phase 2 Step B (Bookkeeping integrity: system accounts, V3 deferred constraint trigger, Account debit/credit, AccountService, ReconciliationService, and comprehensive test suite) was committed and pushed by the human.
  - Both commits are on branch `main` and pushed to `origin/main`.
- **Next step proposed:** Phase 2, Step C — Concurrency proof, stale-entity elimination, configurable lock timeout, and concurrency test suite.

### Entry 11 — Phase 2 Step C — Concurrency Proof — 2026-11-16 10:40 IST

- **Status:** DONE (all tasks and verification steps completed and passing)

- **Test-First Execution & Initial Failure Analysis (Task 1):**
  - Following strict TDD, the full concurrency test suite (`TransferConcurrencyIntegrationTest`) was authored and executed against the unmodified production code prior to implementing fixes.
  - **Result:** `BUILD FAILURE` (79 tests run, 3 failures, 1 error):
    ```
    [ERROR]   TransferConcurrencyIntegrationTest.concurrentDepositsSameCurrency:453 Unexpected failure during concurrent deposits: org.springframework.orm.ObjectOptimisticLockingFailureException: Query result contains conflicting version of entity already held in persistence context for entity [com.ledgercore.account.Account with id '...']
    [ERROR]   TransferConcurrencyIntegrationTest.concurrentDepositsToOneAccount:525 Unexpected failure during concurrent deposit/transfer: org.springframework.orm.ObjectOptimisticLockingFailureException: Query result contains conflicting version of entity already held in persistence context for entity [com.ledgercore.account.Account with id '...']
    [ERROR]   TransferConcurrencyIntegrationTest.reconciliationUnderLoad:584 » Execution org.springframework.orm.ObjectOptimisticLockingFailureException: Query result contains conflicting version of entity already held in persistence context for entity [com.ledgercore.account.Account with id '...']
    [ERROR]   TransferConcurrencyIntegrationTest.lockTimeout:634 Expecting code to raise a throwable.
    ```
  - **Root Cause Analysis:**
    1. In `AccountService.deposit()`, calling `accountRepository.findById(accountId)` and `getOrCreateFundingAccount(currency)` loaded managed `Account` entities into Hibernate's first-level persistence context. When `TransferService.executeFunding()` executed `findByIdForUpdate` in the same transaction, Hibernate returned the cached instances. As concurrent threads modified the same accounts in parallel, version checks in PostgreSQL collided with the stale persistence context, throwing `ObjectOptimisticLockingFailureException`.
    2. `lockTimeout` failed because `TransferService` had no lock timeout configured, and did not catch or translate PostgreSQL SQLSTATE `55P03`.

- **Implementation Details:**
  1. **Stale-Entity Elimination (Task 2):**
     - Added projection queries to `AccountRepository`:
       - `findCurrencyById(UUID id)`: returns `Optional<String>`.
       - `findIdByAccountNumber(String accountNumber)`: returns `Optional<UUID>`.
     - Added `AccountService.getOrCreateFundingAccountId(String currency)` returning scalar `UUID` without entity hydration.
     - Refactored `AccountService.deposit()` to query only currency string and funding account UUID, guaranteeing that **zero** `Account` entities are loaded into the persistence context prior to `TransferService` pessimistic row locking.
     - Updated `AccountService.openAccount()` to call `entityManager.detach(saved)` before delegating to `deposit()`, ensuring the freshly saved account is not cached prior to `findByIdForUpdate`.
  2. **Bounded Lock Wait & SQLSTATE Translation (Task 3):**
     - Added `TransferBusyException.java` extending `RuntimeException`.
     - Injected `@Value("${ledgercore.transfer.lock-timeout:3s}") String lockTimeout` and `EntityManager` into `TransferService`.
     - At the start of `TransferService.executeInternal()`: executed `SELECT set_config('lock_timeout', :timeout, true)` to set a transaction-local lock wait timeout in PostgreSQL.
     - Wrapped lock acquisition and execution with cause-chain inspection for PostgreSQL SQLSTATE `55P03` (or messages containing `"lock timeout"`), translating to `TransferBusyException`.
  3. **Code Quality & Small Fixes (Task 4):**
     - Added null command check in `TransferService.validate()` throwing `InvalidTransferRequestException("TransferCommand must not be null")`, verified by unit test `rejectsNullCommand` in `TransferServiceUnitTest`.
     - Refactored `ReconciliationService.checkCurrencyBalanceSums()` to collect rows into an immutable record list `List<CurrencyBalance>` first, updating `balancesByCurrency` and `problems` outside the row mapper lambda. Removed unused import `java.util.UUID`.
  4. **Comprehensive Concurrency Suite (Task 5):**
     - `TransferConcurrencyIntegrationTest.java` covering 9 robust scenarios:
       - **5a (`manyTransfersFromOneAccount`)**: 200 concurrent threads transferring $10 each from a $500 balance; exactly 50 succeed (COMPLETED), 150 fail gracefully (FAILED: Insufficient funds), balance reaches $0, zero double-debits, ledger reconciled. Repeated 3 times.
       - **5b (`oppositeDirectionTransfers`)**: 200 concurrent threads with simultaneous bidirectional transfers (100 A->B, 100 B->A); zero deadlocks, balances return to original, ledger reconciled. Repeated 3 times.
       - **5c (`randomTransfersAcrossAccounts`)**: 500 concurrent transfers across 50 accounts with fixed seed; total money conserved, latency and throughput logged.
       - **5d (`sameKeySameRequestRace`)**: 20 concurrent threads submitting identical key and payload; exactly 1 COMPLETED, 19 replayed, exactly 2 ledger entries inserted.
       - **5e (`sameKeyDifferentPayloadRace`)**: 20 concurrent threads submitting identical key with conflicting payloads; exactly 1 COMPLETED, 19 throw `IdempotencyConflictException`.
       - **5f (`concurrentDepositsSameCurrency`)**: 50 concurrent deposits into different accounts of same currency; system funding account serialized cleanly.
       - **5g (`concurrentDepositsToOneAccount`)**: 10 concurrent deposits and 10 concurrent transfers on the same account; final balance matches net calculation.
       - **5h (`reconciliationUnderLoad`)**: 100 concurrent transfers while background thread executes `reconciliationService.reconcile()` every 50ms; zero ledger inconsistencies detected.
       - **5i (`lockTimeout`)**: Account locked in separate transaction; concurrent transfer times out within 3s throwing `TransferBusyException`; after lock release, transfer succeeds.

- **Verification Outputs (Task 6):**
  1. `mvnw.cmd test` without `JAVA_TOOL_OPTIONS` executed **three times consecutively**:
     - **Run 1:**
       ```
       [INFO] Tests run: 80, Failures: 0, Errors: 0, Skipped: 0
       [INFO] BUILD SUCCESS
       [INFO] Total time: 01:14 min
       ```
     - **Run 2:**
       ```
       [INFO] Tests run: 80, Failures: 0, Errors: 0, Skipped: 0
       [INFO] BUILD SUCCESS
       [INFO] Total time: 01:09 min
       ```
     - **Run 3:**
       ```
       [INFO] Tests run: 80, Failures: 0, Errors: 0, Skipped: 0
       [INFO] BUILD SUCCESS
       [INFO] Total time: 01:04 min
       ```
  2. **Benchmark Metrics (Scenario 5c):**
     ```
     Total Transfers: 500, Completed: 500, Business Failures: 0
     Wall Time: 1712-1893 ms, Throughput: ~264-292 tx/sec
     Latency: p50=93-104 ms, p95=208-251 ms, max=355-423 ms
     ```
  3. **Docker Compose DB & Spring Boot Startup Verification:**
     - `docker compose up -d` started `ledgercore-db` (PostgreSQL 18.7 healthy on host port 5433).
     - `mvnw.cmd spring-boot:run` log:
       ```
       2026-11-16T05:08:39.350Z INFO 20560 --- [ledgercore] [main] o.f.core.internal.command.DbValidate : Successfully validated 3 migrations (execution time 00:00.040s)
       2026-11-16T05:08:39.383Z INFO 20560 --- [ledgercore] [main] o.f.core.internal.command.DbMigrate  : Current version of schema "public": 3
       2026-11-16T05:08:39.388Z INFO 20560 --- [ledgercore] [main] o.f.core.internal.command.DbMigrate  : Schema "public" is up to date. No migration necessary.
       2026-11-16T05:08:41.354Z INFO 20560 --- [ledgercore] [main] j.LocalContainerEntityManagerFactoryBean : Initialized JPA EntityManagerFactory for persistence unit 'default'
       2026-11-16T05:08:42.817Z INFO 20560 --- [ledgercore] [main] o.s.boot.tomcat.TomcatWebServer      : Tomcat started on port 8081 (http) with context path '/'
       2026-11-16T05:08:42.827Z INFO 20560 --- [ledgercore] [main] com.ledgercore.LedgercoreApplication : Started LedgercoreApplication in 6.681 seconds
       ```
     - `docker compose stop` stopped container cleanly.
  4. **Flyway Migration Immutability Check:**
     - `git diff -- src/main/resources/db/migration/` returned empty (0 modifications across V1, V2, and V3).

- **Suggested commit message:**
  ```
  feat(concurrency): prove concurrency safety, eliminate stale entities, and add bounded lock timeout

  - AccountRepository: add scalar projections findCurrencyById and findIdByAccountNumber
  - AccountService: add getOrCreateFundingAccountId, eliminate entity caching before locking, detach saved account in openAccount
  - TransferBusyException: unchecked domain exception for lock wait timeouts
  - TransferService: add ledgercore.transfer.lock-timeout configuration (default 3s), set_config lock_timeout per transaction, translate SQLSTATE 55P03 to TransferBusyException, validate null command
  - ReconciliationService: non-mutating row mapping in checkCurrencyBalanceSums, clean unused imports
  - TransferConcurrencyIntegrationTest: 9 concurrency proof scenarios (80 total tests across all suites passing with 0 failures, 0 errors, 0 flaky)
  - docs/AGENT_REPORT.md: mark open issue #4 resolved, document test-first results and benchmarks
  ```

- **Next step proposed:**
  Phase 3: HTTP API layer (REST controllers, OpenAPI documentation, request/response DTOs, and MockMvc / HTTP integration tests).
