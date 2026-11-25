# LedgerCore Agent Report

## Current status
- **Phase / step:** Phase 4, Step A — Authentication and Authorization (JWT + RBAC + Ownership Policy)
- **Overall:** DONE
- **Last updated:** 2026-11-23T23:30 IST
- **One-line summary:** Phase 4 Step A completed: Spring Security 7 & OAuth2 Resource Server integrated; V5__users.sql added; stateless JWT (HS256) auth with fail-fast 32-byte secret validation; BCrypt hashing with 72-byte UTF-8 cap & constant-time dummy hashing on unknown email; caller ownership policy concealing peer & system accounts behind 404; RBAC restricting /api/v1/admin/** to ROLE_ADMIN; deposit restriction with demo self-service override; 130 tests passing with 0 failures, 0 errors; live API verified with curl.

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
None! All 130 unit and integration tests are passing cleanly across all 13 test classes (0 failures, 0 errors, 0 skipped).

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
       - **5a (`manyTransfersFromOneAccount`)** [corrected]: Source account opened with initial balance of 10,000L ($100.00) in USD. 200 concurrent transfers of 100L ($1.00) each to 200 distinct destination accounts (each opened with 0L balance). Exactly 100 succeed (`TransferStatus.COMPLETED`), exactly 100 fail (`TransferStatus.FAILED` with failureReason containing "Insufficient funds"), source final balance is exactly 0L, total destination accounts balance is exactly 10,000L, and reconciliation is consistent. Repeated 3 times.
       - **5b (`oppositeDirectionTransfers`)** [corrected]: Two accounts A and B each opened with initial balance of 100,000L ($1,000.00) in USD. 100 concurrent A->B transfers of 100L each and 100 concurrent B->A transfers of 100L each (200 transfers total). Zero deadlocks, all 200 complete (`TransferStatus.COMPLETED`), final balances of both A and B remain exactly 100,000L, and reconciliation is consistent. Repeated 3 times.
       - **5c (`randomTransfersAcrossAccounts`)** [corrected]: 50 accounts each opened with 10,000L ($100.00) in USD (total initial money 500,000L). 500 concurrent transfers across random accounts generated with fixed RNG seed `123456789L` and transfer amounts from 1L to 200L. All 500 transfers execute without unexpected errors, total money across all 50 accounts is conserved at exactly 500,000L, and latency percentiles (p50, p95, max) and throughput are logged. Reconciliation is consistent.
       - **5d (`sameKeySameRequestRace`)** [corrected]: Source account opened with 5,000L, destination with 1,000L. 20 concurrent threads submit the same idempotency key with identical payload (transfer 500L USD). Exactly 1 non-replayed transfer succeeds (`replayed = false`), exactly 19 replay the stored transfer (`replayed = true`), all return `COMPLETED` with matching transferId. Source final balance is 4,500L, destination balance is 1,500L, and exactly 2 ledger entries exist in the database.
       - **5e (`sameKeyDifferentPayloadRace`)** [corrected]: Source account opened with 10,000L, destination X with 1,000L, destination Y with 1,000L. 10 concurrent threads submit the same idempotency key: 5 threads submit payload X (100L to destination X) and 5 threads submit payload Y (200L to destination Y). Exactly 1 winner stores a transfer (`winnerCount == 1`), 5 threads with the competing payload throw `IdempotencyConflictException` (`conflictCount == 5`), and 4 other threads with the winning payload replay the stored transfer (`replayCount == 4`, `replayed == true`). Source balance drops by exactly the winning payload amount (10,000L - winningAmount), winning destination gains winningAmount, losing destination remains unchanged at 1,000L. Exactly 1 transfer row and 2 ledger entries stored; reconciliation consistent.
       - **5f (`concurrentDepositsSameCurrency`)** [corrected]: Initial funding account balance recorded before test. 50 concurrent `openAccount` calls with initial deposit of 1,000L each for currency "USD" (50 threads). All 50 succeed with each account having balance 1,000L; system funding account balance is negative and the change equals exactly `-(50 * 1000L) = -50,000L`; reconciliation consistent with 0 problems.
       - **5g (`concurrentDepositsToOneAccount`)** [corrected]: Target account opened with 5,000L, sink account with 0L. 30 concurrent deposit threads (depositing 200L each = 6,000L total) and 10 concurrent transfer threads (transferring 100L each to sink = 1,000L total) execute simultaneously on the target account (40 total concurrent threads). All succeed without exceptions. Target final balance equals exactly 5,000 + 6,000 - 1,000 = 10,000L; sink balance is 1,000L; reconciliation consistent.
       - **5h (`reconciliationUnderLoad`)** [corrected]: Source account opened with 50,000L, 100 destination accounts opened with 0L. 100 concurrent transfers of 100L each from source to destinations while a background worker thread executes `reconciliationService.reconcile()` every 50ms until all transfers complete. Zero reconciliation inconsistencies detected during the run; final reconciliation report consistent.
       - **5i (`lockTimeout`)** [corrected]: Moved to dedicated test class `TransferLockTimeoutIntegrationTest` configured with `ledgercore.transfer.lock-timeout=500ms`. Transaction 1 acquires and holds a pessimistic lock (`findByIdForUpdate`) on an account. Transaction 2 attempts a transfer from the locked account, times out after 500ms, and throws `TransferBusyException` within bounded wall-clock time (< 3 seconds, proving the per-transaction `lock_timeout` took effect rather than waiting for global defaults). After Transaction 1 commits and releases the lock, Transaction 2 succeeds.

- **Corrections to previous report text (documented per Phase 2C-FIX Task 2):**
  - **Test 5a (`manyTransfersFromOneAccount`)**: Previously described as 200 threads transferring $10 each from a $500 balance with 50 succeeding and 150 failing. In actual code, source was opened with 10,000L ($100), transfer amount was 100L ($1), and exactly 100 succeeded and 100 failed.
  - **Test 5e (`sameKeyDifferentPayloadRace`)**: Previously described as 20 threads with 19 throwing `IdempotencyConflictException`. In actual code, there were 10 threads (5 payload X, 5 payload Y); exactly 1 winner, 5 conflicts, and 4 replays.
  - **Test 5g (`concurrentDepositsToOneAccount`)**: Previously described as 10 concurrent deposits and 10 concurrent transfers. In actual code, there were 30 deposit threads (each 200L) and 10 transfer threads (each 100L).
  - **Test 5f error message discrepancy**: The "before fix" failure snippet in Entry 11 cited `Unexpected failure during concurrent deposits:`, whereas the test assertion message in code is `Concurrent openAccount with deposit failed:`. Since the un-fixed code is no longer present, this cannot be reproduced verbatim; the discrepancy is noted here explicitly.

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

### Entry 12 — Branch Switch to phase-3a-rest-api — 2026-11-16 11:45 IST

- **Status:** BRANCH CREATED & ACTIVE
- **What the human verified and committed:**
  - The human made a WIP commit of Phase 2C on branch `phase-2c-concurrency` and merged/committed to `main` (commit `6c2ca7f`).
  - Active work moved to branch `phase-3a-rest-api`.
- **Next step proposed:** Phase 3, Step A — REST API for accounts and transfers (thin controllers, DTOs, ProblemDetail RFC 9457 error handling, V4 statement index, request-id filter, read-only query services, and comprehensive integration tests).

### Entry 13 — Phase 3, Step A: REST API for Accounts and Transfers (2026-11-16 20:25 IST)

- **Status:** COMPLETED & VERIFIED
- **Goal:** Implement REST API for customer accounts, deposits, transfers, statements, and admin reconciliation with zero business logic in controllers, RFC 9457 ProblemDetail error handling, request ID tracing, V4 migration, and thorough integration testing.

- **Files Created / Modified:**
  1. `src/main/resources/db/migration/V4__ledger_statement_index.sql`: creates composite index `ledger_entries_statement_idx ON ledger_entries (account_id, created_at DESC, id DESC)` for fast newest-first statement queries.
  2. `src/main/java/com/ledgercore/ledger/LedgerEntryRepository.java`: added `findByAccountIdOrderByCreatedAtDescIdDesc(UUID accountId, Pageable pageable)` returning `Slice<LedgerEntry>`.
  3. `src/main/java/com/ledgercore/transfer/TransferNotFoundException.java`: unchecked domain exception for transfer lookups.
  4. `src/main/java/com/ledgercore/api/dto/*`:
     - `CreateAccountRequest.java`: Java record with `@NotBlank`, `@Size`, `@Pattern(regexp = "^[A-Z]{3}$")`, `@PositiveOrZero`, `@Max(9007199254740991)`.
     - `AccountResponse.java`: Java record exposing public account representation.
     - `DepositRequest.java`: Java record with `@NotNull`, `@Positive`, `@Max(9007199254740991)`.
     - `TransferRequest.java`: Java record with `@NotNull`, `@Positive`, `@Max(9007199254740991)`, `@Pattern(regexp = "^[A-Z]{3}$")`.
     - `TransferResponse.java`: Java record with transfer execution details.
     - `StatementEntryResponse.java` & `StatementResponse.java`: custom DTOs for slice paging without exposing Spring Data internals.
  5. `src/main/java/com/ledgercore/account/AccountQueryService.java`: `@Transactional(readOnly = true)` service concealing internal SYSTEM accounts behind `404 Not Found`.
  6. `src/main/java/com/ledgercore/transfer/TransferQueryService.java`: `@Transactional(readOnly = true)` service for retrieving transfer details by ID.
  7. `src/main/java/com/ledgercore/api/IdempotencyKeyValidator.java`: validates `Idempotency-Key` header against `^[\x21-\x7E]{1,128}$`.
  8. `src/main/java/com/ledgercore/api/filter/RequestIdFilter.java`: validates incoming `X-Request-Id` against `^[A-Za-z0-9._-]{1,64}$` (replaces invalid with UUID to prevent log injection), injects into MDC, echoes header on responses, logs exactly one access line per request.
  9. `src/main/java/com/ledgercore/api/error/GlobalExceptionHandler.java`: `@Order(Ordered.HIGHEST_PRECEDENCE)` `@RestControllerAdvice` mapping validation errors, domain exceptions, type mismatches, not readable, missing headers, conflict, and 503 lock timeout to RFC 9457 `ProblemDetail` with `requestId` and detailed `errors` map. Unexpected errors produce a generic 500 without leaking stack traces or SQL.
  10. `src/main/java/com/ledgercore/api/controller/AccountController.java`: endpoints 1, 2, 3, 4 (`POST /api/v1/accounts`, `GET /api/v1/accounts/{id}`, `GET /api/v1/accounts/{id}/entries`, `POST /api/v1/accounts/{id}/deposits`).
  11. `src/main/java/com/ledgercore/api/controller/TransferController.java`: endpoints 5, 6 (`POST /api/v1/transfers`, `GET /api/v1/transfers/{id}`).
  12. `src/main/java/com/ledgercore/api/controller/AdminController.java`: endpoint 7 (`GET /api/v1/admin/reconciliation`) with code comment noting Phase 4 auth requirement.
  13. `src/main/resources/application.properties`: added `server.address=127.0.0.1` and `spring.mvc.problemdetails.enabled=true`.
  14. `src/test/java/com/ledgercore/api/TestErrorController.java`: test-only controller simulating unexpected exceptions.
  15. `src/test/java/com/ledgercore/api/RestApiIntegrationTest.java`: 13 comprehensive end-to-end HTTP tests running against random port via `java.net.http.HttpClient` (JDK 25 built-in, chosen because Boot 4's `TestRestTemplate` requires `RestTemplateBuilder` which is not present on the runtime classpath).
  16. `docs/API.md`: complete API documentation with endpoint specifications, status code mapping table, idempotency semantics, JavaScript safe-integer limit rationale, known limitations, and unauthenticated usage warning.
  17. `docs/api-examples.http`: ready-to-run HTTP requests covering all 7 endpoints and failure scenarios.

- **Verification Outputs (Pasted Real Output):**

  1. **Full Test Suite (`.\mvnw.cmd test` WITHOUT `JAVA_TOOL_OPTIONS`):**
     ```text
     [INFO] Scanning for projects...
     [INFO] ---------------------< com.ledgercore:ledgercore >----------------------
     [INFO] Building  0.0.1-SNAPSHOT
     [INFO] --------------------------------[ jar ]---------------------------------
     [INFO] --- surefire:3.5.6:test (default-test) @ ledgercore ---
     [INFO] Running com.ledgercore.account.AccountUnitTest
     [INFO] Tests run: 13, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.331 s -- in com.ledgercore.account.AccountUnitTest
     [INFO] Running com.ledgercore.api.RestApiIntegrationTest
     [INFO] Tests run: 13, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 24.46 s -- in com.ledgercore.api.RestApiIntegrationTest
     [INFO] Running com.ledgercore.DomainIntegrationTest
     [INFO] Tests run: 10, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.771 s -- in com.ledgercore.DomainIntegrationTest
     [INFO] Running com.ledgercore.LedgercoreApplicationTests
     [INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.007 s -- in com.ledgercore.LedgercoreApplicationTests
     [INFO] Running com.ledgercore.common.MoneyTest
     [INFO] Tests run: 8, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.005 s -- in com.ledgercore.common.MoneyTest
     [INFO] Running com.ledgercore.transfer.TransferConcurrencyIntegrationTest
     [INFO] Tests run: 13, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 43.51 s -- in com.ledgercore.transfer.TransferConcurrencyIntegrationTest
     [INFO] Running com.ledgercore.transfer.TransferServiceIntegrationTest
     [INFO] Tests run: 17, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 1.508 s -- in com.ledgercore.transfer.TransferServiceIntegrationTest
     [INFO] Running com.ledgercore.transfer.TransferServiceUnitTest
     [INFO] Tests run: 18, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.054 s -- in com.ledgercore.transfer.TransferServiceUnitTest
     [INFO] 
     [INFO] Results:
     [INFO] 
     [INFO] Tests run: 93, Failures: 0, Errors: 0, Skipped: 0
     [INFO] 
     [INFO] ------------------------------------------------------------------------
     [INFO] BUILD SUCCESS
     [INFO] ------------------------------------------------------------------------
     [INFO] Total time:  01:23 min
     [INFO] Finished at: 2026-11-16T20:19:44+05:30
     ```

  2. **Live Docker Compose & Spring Boot Application Verification via curl:**
     - `docker compose up -d`: started `ledgercore-db` on port 5433 (PostgreSQL 18.7 healthy).
     - `mvnw.cmd spring-boot:run`: started Tomcat on port 8081 with context path `/` and successfully validated 4 Flyway migrations (including V4).
     
     **a. Create Account 1 (Alice):**
     ```text
     HTTP/1.1 201 
     X-Request-Id: 0b64e38e-8f7d-4ce8-870e-ddf1c5d9912a
     Location: /api/v1/accounts/aae97fa2-7dc1-4d14-877f-2dd8f8ab8310
     Content-Type: application/json
     Transfer-Encoding: chunked
     Date: Mon, 16 Nov 2026 14:53:08 GMT

     {"id":"aae97fa2-7dc1-4d14-877f-2dd8f8ab8310","accountNumber":"ACC-e8fb559d-a1bf-43c1-880c-84908b43ff45","ownerRef":"Alice","currency":"USD","status":"ACTIVE","balanceMinor":10000,"createdAt":"2026-11-16T14:53:08.177477Z","updatedAt":"2026-11-16T14:53:08.529184200Z"}
     ```

     **b. Create Account 2 (Bob):**
     ```text
     HTTP/1.1 201 
     X-Request-Id: 4f20ebec-30a0-4182-aa2f-e214441cf83d
     Location: /api/v1/accounts/6c3c26e6-3616-4cc1-9db7-eb3ea929db45
     Content-Type: application/json
     Transfer-Encoding: chunked
     Date: Mon, 16 Nov 2026 14:53:15 GMT

     {"id":"6c3c26e6-3616-4cc1-9db7-eb3ea929db45","accountNumber":"ACC-369b0022-c039-4fa7-bcd4-8983b0dc153a","ownerRef":"Bob","currency":"USD","status":"ACTIVE","balanceMinor":0,"createdAt":"2026-11-16T14:53:15.204419500Z","updatedAt":"2026-11-16T14:53:15.204419500Z"}
     ```

     **c. Deposit Funds into Bob's Account:**
     ```text
     HTTP/1.1 201 
     X-Request-Id: 2dc47046-c1c6-445c-9336-96e8eac0dff1
     Location: /api/v1/transfers/d7713831-efe8-4161-9449-415273f8cc8f
     Content-Type: application/json
     Transfer-Encoding: chunked
     Date: Mon, 16 Nov 2026 14:53:23 GMT

     {"transferId":"d7713831-efe8-4161-9449-415273f8cc8f","status":"COMPLETED","failureReason":null,"fromAccountId":"6a58f834-613a-42a7-a6dd-efe1fe1bc5a5","toAccountId":"6c3c26e6-3616-4cc1-9db7-eb3ea929db45","amountMinor":5000,"currency":"USD","createdAt":"2026-11-16T14:53:22.975826Z"}
     ```

     **d. Transfer Funds (Alice -> Bob):**
     ```text
     HTTP/1.1 201 
     X-Request-Id: bec9f1a9-17bd-4fbd-a4d3-5f432aa1241d
     Location: /api/v1/transfers/b269f2e9-6859-4875-b79a-3cef3d3c5117
     Content-Type: application/json
     Transfer-Encoding: chunked
     Date: Mon, 16 Nov 2026 14:53:29 GMT

     {"transferId":"b269f2e9-6859-4875-b79a-3cef3d3c5117","status":"COMPLETED","failureReason":null,"fromAccountId":"aae97fa2-7dc1-4d14-877f-2dd8f8ab8310","toAccountId":"6c3c26e6-3616-4cc1-9db7-eb3ea929db45","amountMinor":2500,"currency":"USD","createdAt":"2026-11-16T14:53:29.477201Z"}
     ```

     **e. Replay Transfer (Idempotent Replay):**
     ```text
     HTTP/1.1 201 
     X-Request-Id: 759debde-15b1-4bd6-ac82-186295bd692f
     Location: /api/v1/transfers/b269f2e9-6859-4875-b79a-3cef3d3c5117
     Idempotent-Replayed: true
     Content-Type: application/json
     Transfer-Encoding: chunked
     Date: Mon, 16 Nov 2026 14:53:43 GMT

     {"transferId":"b269f2e9-6859-4875-b79a-3cef3d3c5117","status":"COMPLETED","failureReason":null,"fromAccountId":"aae97fa2-7dc1-4d14-877f-2dd8f8ab8310","toAccountId":"6c3c26e6-3616-4cc1-9db7-eb3ea929db45","amountMinor":2500,"currency":"USD","createdAt":"2026-11-16T14:53:29.477201Z"}
     ```

     **f. Transfer Failure (Insufficient Funds -> 422 ProblemDetail):**
     ```text
     HTTP/1.1 422 
     X-Request-Id: da6954e2-274f-43f4-aa5c-7a1469ce3146
     Content-Type: application/problem+json
     Transfer-Encoding: chunked
     Date: Mon, 16 Nov 2026 14:54:01 GMT

     {"detail":"Insufficient funds: balance=7500, requested=999999999","instance":"/api/v1/transfers","status":422,"title":"Transfer Failed","requestId":"da6954e2-274f-43f4-aa5c-7a1469ce3146","transferId":"e9ca438e-ec49-4296-ab49-cee3147e4e13","transferStatus":"FAILED","failureReason":"Insufficient funds: balance=7500, requested=999999999"}
     ```

     **g. Ledger Statement for Account Alice:**
     ```text
     HTTP/1.1 200 
     X-Request-Id: 8e9de3a0-ac25-48a5-8bbc-60e8c8e28370
     Content-Type: application/json
     Transfer-Encoding: chunked
     Date: Mon, 16 Nov 2026 14:54:13 GMT

     {"items":[{"entryId":"34c3f8d6-443c-4960-b41c-804b62905bfe","transferId":"b269f2e9-6859-4875-b79a-3cef3d3c5117","direction":"DEBIT","amountMinor":2500,"currency":"USD","createdAt":"2026-11-16T14:53:29.484829Z"},{"entryId":"eefe975c-67af-4c1e-93c1-1e83122b732d","transferId":"000b44ce-ca28-48fd-ae20-9af016fc13ad","direction":"CREDIT","amountMinor":10000,"currency":"USD","createdAt":"2026-11-16T14:53:08.524032Z"}],"page":0,"size":20,"hasNext":false}
     ```

     **h. Admin Reconciliation Integrity Check:**
     ```text
     HTTP/1.1 200 
     X-Request-Id: 958d04e2-5f8a-4574-8168-948743dc3564
     Content-Type: application/json
     Transfer-Encoding: chunked
     Date: Mon, 16 Nov 2026 14:54:20 GMT

     {"consistent":true,"problems":[],"balancesByCurrency":{"USD":0}}
     ```

     - Clean shutdown: stopped Spring Boot application and executed `docker compose stop`.

  3. **Flyway Migration Immutability Check:**
     - `git diff -- src/main/resources/db/migration/V1__init_schema.sql src/main/resources/db/migration/V2__ledger_hardening.sql src/main/resources/db/migration/V3__system_accounts_and_journal_balance.sql` returned completely empty (0 modifications across earlier migrations).

- **Suggested commit message:**
  ```text
  feat(api): implement Phase 3A REST API for accounts, transfers, and statements

  - V4__ledger_statement_index.sql: add composite index on ledger_entries(account_id, created_at DESC, id DESC)
  - LedgerEntryRepository: add Slice-based newest-first statement query
  - DTO records: CreateAccountRequest, AccountResponse, DepositRequest, TransferRequest, TransferResponse, StatementEntryResponse, StatementResponse
  - AccountQueryService & TransferQueryService: @Transactional(readOnly = true) read models
  - RequestIdFilter: validate X-Request-Id against log injection, populate MDC, echo header, single access line logging
  - IdempotencyKeyValidator: enforce ^[\x21-\x7E]{1,128}$ on mutating commands
  - GlobalExceptionHandler: @Order(Ordered.HIGHEST_PRECEDENCE) RFC 9457 ProblemDetail handling for all validations, domain errors, and safe 500 fallback
  - AccountController, TransferController, AdminController: 7 endpoints at /api/v1
  - RestApiIntegrationTest: 13 HTTP integration tests via java.net.http.HttpClient on RANDOM_PORT
  - docs/API.md & docs/api-examples.http: complete REST API documentation and verified HTTP requests
  ```

- **Next step proposed:**
  Phase 3 Step B or Phase 4 (Authentication & Authorization: API keys, JWT/OAuth2 tokens, role-based access control protecting admin endpoints).

### Entry 14 — Phase 2C-FIX — Truthfulness, Evidence, and Test Hardening — 2026-11-23 19:18 IST

- **Status:** DONE (all tasks and verification steps completed and passing)

- **What was done:**
  1. **RULE 10 Added to docs/AGENT_RULES.md (Task 1):**
     - Enforced: "Reports must quote real command output verbatim. Descriptions of tests and code must be produced by READING the current source file, never from memory. If a number, name or behavior in the report cannot be backed by a file or a pasted output, do not write it."
  2. **Entry 11 Test Descriptions Corrected (Task 2):**
     - Re-read `TransferConcurrencyIntegrationTest.java` and rewrote descriptions for scenarios 5a–5i, matching every exact number, parameter, and thread count in the code.
     - Marked all corrected lines with `[corrected]`.
     - Appended an itemized list of previous inaccuracies (5a balance/threads/failures, 5e thread counts and conflict ratio, 5g deposit vs transfer thread counts, and 5f error message reproduction discrepancy).
  3. **Test 5f Hardened (Task 3):**
     - In `concurrentDepositsSameCurrency`, recorded `initialFundingBalance` of the currency's system funding account prior to releasing concurrent `openAccount` tasks.
     - Asserted that the net change in funding balance equals exactly `-(count * depositPerAccount)` (`-(50 * 1000L) = -50_000L`).
  4. **Test 5e Hardened (Task 4):**
     - In `sameKeyDifferentPayloadRace`, asserted exact counts: `winnerCount == 1`, `conflictCount == 5`, `replayCount == 4`.
     - Tracked `winningPayload` ("X" vs "Y") and verified that the source account was debited by exactly `winningAmount` (100L or 200L), winning destination was credited by `winningAmount`, and losing destination remained untouched at initial balance (1000L).
  5. **Test 5i Isolated & Testcontainers Singleton (Task 5):**
     - Moved lock timeout test to dedicated test class `TransferLockTimeoutIntegrationTest` configured with `ledgercore.transfer.lock-timeout=500ms`.
     - Asserted lock timeout elapsed strictly under 3 seconds (observed ~517–523 ms), verifying transaction-local timeout overrides global 5s default.
     - Refactored `TestcontainersConfiguration` to use a shared static `PostgreSQLContainer` singleton instance across Spring test application contexts.
  6. **Deterministic Stale-Entity Pitfall Test (Task 6):**
     - Created `StaleEntityPitfallIntegrationTest`:
       - Transaction 1 loads entity via `findById`.
       - Thread 2 modifies account via `accountService.deposit` (updating DB row and incrementing `@Version` to 2).
       - Transaction 1 attempts `accountRepository.findByIdForUpdate`.
       - **Observed Hibernate 7 behavior:** Spring / Hibernate detects that the row returned by PostgreSQL has a newer version (`2`) than the entity already cached in the persistence context (`1`), throwing `org.springframework.orm.ObjectOptimisticLockingFailureException: Query result contains conflicting version of entity already held in persistence context`.
       - Showed opposite case: when Transaction 1 does not load entity beforehand, `findByIdForUpdate` returns fresh committed state (balance 5000L).
  7. **Lock Timeout SQLState Investigation (Task 7):**
     - Tested PostgreSQL lock timeout behavior: PostgreSQL reliably emits SQLSTATE `55P03` wrapped in `PSQLException` -> `LockTimeoutException` -> `CannotAcquireLockException`.
     - Removed redundant message-sniffing string checks from `TransferService.isLockTimeout()`.
     - Added 6 unit tests with fabricated exception chains in `TransferServiceUnitTest` verifying SQLState `55P03` detection and false returns for null / other SQLStates.
  8. **Three Consecutive Test Runs (Task 8):**
     - Executed `mvnw.cmd test` WITHOUT `JAVA_TOOL_OPTIONS` 3 consecutive times. All passed with 101 tests, 0 failures, 0 errors.
     - Created `docs/evidence/phase-2c-runs.md` pasting verbatim output summaries, timestamps, git statuses, and benchmark log lines.
  9. **Performance Documentation (Task 9):**
     - Created `docs/PERFORMANCE.md` documenting throughput (207.81 – 265.96 tx/sec), p50 latency (99 – 123 ms), p95 latency (246 – 349 ms), max latency (524 – 752 ms), hardware specifications (AMD Ryzen 5 7520U, 4 cores / 8 logical processors, 16 GB RAM), and environmental caveats.
  10. **Flyway Migration Immutability Check (Task 10):**
     - `git diff -- src/main/resources/db/migration/` returned empty (0 modifications).

- **Exact Test Counts per Test Class (101 Total Tests):**
  - `com.ledgercore.account.AccountUnitTest`: 13
  - `com.ledgercore.api.RestApiIntegrationTest`: 13
  - `com.ledgercore.common.MoneyTest`: 8
  - `com.ledgercore.DomainIntegrationTest`: 10
  - `com.ledgercore.LedgercoreApplicationTests`: 1
  - `com.ledgercore.transfer.StaleEntityPitfallIntegrationTest`: 2
  - `com.ledgercore.transfer.TransferConcurrencyIntegrationTest`: 12
  - `com.ledgercore.transfer.TransferLockTimeoutIntegrationTest`: 1
  - `com.ledgercore.transfer.TransferServiceIntegrationTest`: 17
  - `com.ledgercore.transfer.TransferServiceUnitTest`: 24
  - **Total:** 101 tests run, 0 failures, 0 errors, 0 skipped.




### Entry 15 — Phase 4, Step A: Authentication and Authorization (JWT) — 2026-11-23 23:30 IST

- **Status:** COMPLETED & VERIFIED
- **Goal:** Implement stateless JWT authentication, role-based authorization (RBAC), caller ownership policies, timing-attack-resistant login, BCrypt password policy, fail-fast JWT startup validation, and thorough integration testing with zero test regressions.

- **Approved New Dependencies Added (from Spring Boot BOM, versions managed by BOM):**
  1. `org.springframework.boot:spring-boot-starter-security`
  2. `org.springframework.boot:spring-boot-starter-oauth2-resource-server`
  3. `org.springframework.boot:spring-boot-starter-security-test`

- **Database Migrations Added:**
  - `src/main/resources/db/migration/V5__users.sql`:
    - Creates `users` table: `id UUID PRIMARY KEY`, `email VARCHAR(254) NOT NULL`, `password_hash VARCHAR(100) NOT NULL`, `role VARCHAR(10) NOT NULL CHECK (role IN ('CUSTOMER', 'ADMIN'))`, `enabled BOOLEAN NOT NULL DEFAULT TRUE`, `created_at TIMESTAMPTZ NOT NULL`, `updated_at TIMESTAMPTZ NOT NULL`.
    - Creates unique case-insensitive index: `CREATE UNIQUE INDEX users_email_lower_uidx ON users (lower(email))`.
    - Adds `owner_user_id UUID REFERENCES users(id)` column to `accounts` table with foreign key index `accounts_owner_user_id_idx`.

- **Files Created / Modified:**
  1. `src/main/java/com/ledgercore/user/*`:
     - `Role.java`: Enum representing `CUSTOMER` and `ADMIN`.
     - `User.java`: JPA entity with UUID ID, email, passwordHash, role, enabled flag, audit timestamps, and factory methods.
     - `UserRepository.java`: Spring Data JPA repository with `findByEmailIgnoreCase(String email)` and `existsByRole(Role role)`.
  2. `src/main/java/com/ledgercore/account/*`:
     - `Account.java`: Added `ownerUserId` column, getter, and factory overloads.
     - `AccountRepository.java`: Added `findByOwnerUserId(UUID ownerUserId, Pageable pageable)` returning `Slice<Account>`, `findOwnerUserIdById(UUID id)`, and partial index conflict target on `insertSystemFundingIfAbsent`.
     - `AccountService.java`: Added `openAccount` overloads accepting `ownerUserId`.
     - `AccountQueryService.java`: Added caller-aware `getCallerAccounts`, `getAccount`, and `getStatement`.
  3. `src/main/java/com/ledgercore/security/*`:
     - `PasswordPolicy.java`: Validates password length >= 12 chars and <= 72 UTF-8 bytes to strictly enforce BCrypt's 72-byte truncation boundary.
     - `JwtService.java`: Issues HS256 JWT tokens using Nimbus with claims `iss=ledgercore`, `sub=userId`, `iat`, `exp` (15m configurable), `role`, `jti`. Enforces fail-fast startup check requiring at least 32 bytes of secret entropy without logging or leaking the secret.
     - `AuthenticatedUser.java`: Extracts authenticated user details (user ID, role) from Spring Security `Authentication` / `Jwt`.
     - `AccountOwnershipPolicy.java`: Reusable service policy enforcing caller ownership. Conceals other users' accounts, statements, and transfers behind `404 Not Found`. Conceals internal `SYSTEM` accounts behind `404 Not Found` from all users (including Admins).
     - `CustomAuthenticationEntryPoint.java`: Returns RFC 9457 `ProblemDetail` with status 401, `WWW-Authenticate: Bearer`, and `requestId`. Never leaks token rejection reasons.
     - `CustomAccessDeniedHandler.java`: Returns RFC 9457 `ProblemDetail` with status 403 and `requestId`.
     - `SecurityConfig.java`: Configures stateless session management, CSRF disabled, CORS disabled, permitAll on `POST /api/v1/auth/register`, `POST /api/v1/auth/login`, and `GET /actuator/health`, `ROLE_ADMIN` required on `/api/v1/admin/**`, `anyRequest().authenticated()`, and wired Nimbus JWT decoder with zero clock skew.
     - `SelfServiceDepositStartupLogger.java`: Emits a loud `WARN` banner at startup if demo self-service deposits are enabled.
  4. `src/main/java/com/ledgercore/auth/*`:
     - `RegisterRequest.java`, `RegisterResponse.java`, `LoginRequest.java`, `AuthResponse.java`: DTO records.
     - `InvalidPasswordException.java`: Domain exception mapped to 400 Bad Request.
     - `EmailAlreadyExistsException.java`: Domain exception mapped to 409 Conflict.
     - `AuthService.java`: User registration and authentication. Normalizes email, validates password against BCrypt 72-byte limit, and mitigates timing attacks by running a dummy BCrypt comparison if the email is not found.
     - `AdminBootstrapService.java`: `ApplicationRunner` bootstrapping initial admin user from `ADMIN_EMAIL` and `ADMIN_PASSWORD` environment variables if no admin exists; logs only email, never password.
     - `AuthController.java`: `@RestController` at `/api/v1/auth` exposing `/register` (201) and `/login` (200), carrying `Cache-Control: no-store`.
  5. `src/main/java/com/ledgercore/api/controller/*`:
     - `AccountController.java`: Sets account ownership to caller's user ID, enforces deposit permissions (Admin only, unless demo flag enabled), adds `GET /api/v1/accounts` listing caller's accounts, and routes lookups through ownership policy.
     - `TransferController.java`: Verifies caller owns source account, verifies destination is non-system, and enforces read access on `GET /api/v1/transfers/{id}`.
  6. `src/main/java/com/ledgercore/api/dto/*`:
     - `CreateAccountRequest.java`: Made `ownerRef` optional (defaults to caller's User ID).
     - `AccountListResponse.java`: Response DTO for paginated list of accounts.
  7. `src/main/java/com/ledgercore/api/error/GlobalExceptionHandler.java`:
     - Added exception handlers for `InvalidPasswordException` (400), `BadCredentialsException` (401), `AccessDeniedException` (403), and `EmailAlreadyExistsException` (409).
  8. `docs/*`:
     - `docs/API.md`: Updated API specification documenting authentication, JWT headers, ownership rules, role restrictions, and updated endpoints.
     - `docs/SECURITY.md`: Authored comprehensive security architecture document and STRIDE threat model.
     - `docs/api-examples.http`: Updated with registration, login, customer and admin requests.
  9. `src/test/java/com/ledgercore/*`:
     - `AuthTestHelper.java`: Helper creating customers and admins, issuing real JWT tokens.
     - `RestApiIntegrationTest.java`: Updated all 13 tests to authenticate with valid tokens.
     - `AuthSecurityIntegrationTest.java`: 26 comprehensive tests covering registration, login, timing attack resistance, JWT signature/issuer/alg:none/expiration/tampering checks, deny-by-default, RBAC, 404 concealment, deposit restrictions, and CORS.
     - `SelfServiceDepositsIntegrationTest.java`: 1 test verifying `ledgercore.demo.self-service-deposits=true`.
     - `JwtSecretStartupFailureTest.java`: 2 unit tests verifying fail-fast startup on missing or < 32 byte secret without leaking the secret.

- **Verification Outputs (Pasted Real Output):**

  1. **Full Test Suite (`.\mvnw.cmd test` WITHOUT `JAVA_TOOL_OPTIONS`):**
     ```text
     [INFO] Scanning for projects...
     [INFO] ---------------------< com.ledgercore:ledgercore >----------------------
     [INFO] Building  0.0.1-SNAPSHOT
     [INFO]   from pom.xml
     [INFO] --------------------------------[ jar ]---------------------------------
     [INFO] --- surefire:3.5.6:test (default-test) @ ledgercore ---
     [INFO] Running com.ledgercore.account.AccountUnitTest
     [INFO] Tests run: 13, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.373 s -- in com.ledgercore.account.AccountUnitTest
     [INFO] Running com.ledgercore.api.RestApiIntegrationTest
     [INFO] Tests run: 13, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 24.32 s -- in com.ledgercore.api.RestApiIntegrationTest
     [INFO] Running com.ledgercore.auth.AuthSecurityIntegrationTest
     [INFO] Tests run: 26, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 16.21 s -- in com.ledgercore.auth.AuthSecurityIntegrationTest
     [INFO] Running com.ledgercore.auth.JwtSecretStartupFailureTest
     [INFO] Tests run: 2, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.005 s -- in com.ledgercore.auth.JwtSecretStartupFailureTest
     [INFO] Running com.ledgercore.auth.SelfServiceDepositsIntegrationTest
     [INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 2.768 s -- in com.ledgercore.auth.SelfServiceDepositsIntegrationTest
     [INFO] Running com.ledgercore.DomainIntegrationTest
     [INFO] Tests run: 10, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.812 s -- in com.ledgercore.DomainIntegrationTest
     [INFO] Running com.ledgercore.LedgercoreApplicationTests
     [INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.006 s -- in com.ledgercore.LedgercoreApplicationTests
     [INFO] Running com.ledgercore.common.MoneyTest
     [INFO] Tests run: 8, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.005 s -- in com.ledgercore.common.MoneyTest
     [INFO] Running com.ledgercore.transfer.StaleEntityPitfallIntegrationTest
     [INFO] Tests run: 2, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.285 s -- in com.ledgercore.transfer.StaleEntityPitfallIntegrationTest
     [INFO] Running com.ledgercore.transfer.TransferConcurrencyIntegrationTest
     [INFO] Tests run: 12, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 35.12 s -- in com.ledgercore.transfer.TransferConcurrencyIntegrationTest
     [INFO] Running com.ledgercore.transfer.TransferLockTimeoutIntegrationTest
     [INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 2.283 s -- in com.ledgercore.transfer.TransferLockTimeoutIntegrationTest
     [INFO] Running com.ledgercore.transfer.TransferServiceIntegrationTest
     [INFO] Tests run: 17, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 1.602 s -- in com.ledgercore.transfer.TransferServiceIntegrationTest
     [INFO] Running com.ledgercore.transfer.TransferServiceUnitTest
     [INFO] Tests run: 24, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.061 s -- in com.ledgercore.transfer.TransferServiceUnitTest
     [INFO] 
     [INFO] Results:
     [INFO] 
     [INFO] Tests run: 130, Failures: 0, Errors: 0, Skipped: 0
     [INFO] 
     [INFO] ------------------------------------------------------------------------
     [INFO] BUILD SUCCESS
     [INFO] ------------------------------------------------------------------------
     [INFO] Total time:  01:25 min
     [INFO] Finished at: 2026-11-23T23:24:35+05:30
     [INFO] ------------------------------------------------------------------------
     ```

  2. **Flyway Migration Immutability Check:**
     - Command: `git diff -- src/main/resources/db/migration/V1__init_schema.sql src/main/resources/db/migration/V2__ledger_hardening.sql src/main/resources/db/migration/V3__system_accounts_and_journal_balance.sql src/main/resources/db/migration/V4__ledger_statement_index.sql`
     - Result: Completely empty (0 modifications across V1, V2, V3, and V4).

  3. **Live Docker Compose & Spring Boot Application Verification via curl:**
     - `docker compose up -d`: started `ledgercore-db` on host port 5433 (PostgreSQL 18.7 healthy).
     - `mvnw.cmd spring-boot:run`: started Tomcat on port 8081 with context path `/` and bootstrapped initial admin:
       ```text
       2026-11-23T17:55:24.032Z INFO 24144 --- [ledgercore] [main] o.s.boot.tomcat.TomcatWebServer : Tomcat started on port 8081 (http) with context path '/'
       2026-11-23T17:55:24.042Z INFO 24144 --- [ledgercore] [main] com.ledgercore.LedgercoreApplication : Started LedgercoreApplication in 9.64 seconds
       2026-11-23T17:55:24.584Z INFO 24144 --- [ledgercore] [main] c.ledgercore.auth.AdminBootstrapService : Admin user bootstrapped for email: admin@ledgercore.local
       ```
     - **a. Unauthenticated Request (401 ProblemDetail + WWW-Authenticate):**
       ```text
       HTTP/1.1 401 
       X-Request-Id: 57465bed-2ed8-4ccd-a2a2-16158cba1cf5
       WWW-Authenticate: Bearer
       Content-Type: application/problem+json;charset=UTF-8

       {"detail":"Full authentication is required to access this resource","instance":"/api/v1/accounts","status":401,"title":"Unauthorized","requestId":"57465bed-2ed8-4ccd-a2a2-16158cba1cf5"}
       ```
     - **b. Register Customer (201 Created + Cache-Control: no-store):**
       ```text
       HTTP/1.1 201 
       X-Request-Id: 15ce634f-1911-426d-936f-e18b3383bbff
       Location: /api/v1/users/eea2133b-1026-40bb-8550-b8208bfa8b7c
       Cache-Control: no-store
       Content-Type: application/json

       {"userId":"eea2133b-1026-40bb-8550-b8208bfa8b7c","email":"alice@example.com","role":"CUSTOMER"}
       ```
     - **c. Login Customer (200 OK + JWT Bearer Token):**
       ```text
       HTTP/1.1 200 
       X-Request-Id: 45338c6f-517e-45ef-a020-07e288029fbb
       Cache-Control: no-store
       Content-Type: application/json

       {"accessToken":"eyJhbGciOiJIUzI1NiJ9...","tokenType":"Bearer","expiresInSeconds":900}
       ```
     - **d. Open Account with Customer Bearer Token (201 Created):**
       ```text
       HTTP/1.1 201 
       X-Request-Id: b94236ac-2d0b-4e32-aca0-aa818fded347
       Location: /api/v1/accounts/399fa5db-4a27-46b2-a22f-14afdd64e913
       Content-Type: application/json

       {"id":"399fa5db-4a27-46b2-a22f-14afdd64e913","accountNumber":"ACC-15d7dc47-f246-405e-81a9-4fdf2c1c7a37","ownerRef":"eea2133b-1026-40bb-8550-b8208bfa8b7c","currency":"USD","status":"ACTIVE","balanceMinor":0,"createdAt":"2026-11-23T17:56:22.594435Z","updatedAt":"2026-11-23T17:56:22.594435Z"}
       ```
     - **e. Customer Attempt on Admin Reconciliation Endpoint (403 Forbidden):**
       ```text
       HTTP/1.1 403 
       X-Request-Id: 9ba6b4d4-729f-4eb0-be60-d13db7348999
       Content-Type: application/problem+json;charset=UTF-8

       {"detail":"Access denied: insufficient permissions to access this resource","instance":"/api/v1/admin/reconciliation","status":403,"title":"Forbidden","requestId":"9ba6b4d4-729f-4eb0-be60-d13db7348999"}
       ```
     - **f. Admin Login and Reconciliation Endpoint Access (200 OK):**
       ```text
       HTTP/1.1 200 
       X-Request-Id: be3b3b3c-7185-4390-ba96-82e573af791b
       Content-Type: application/json

       {"consistent":true,"problems":[],"balancesByCurrency":{"USD":0}}
       ```
     - Clean shutdown: stopped Spring Boot application and executed `docker compose stop`.

- **Suggested commit message:**
  ```text
  feat(auth): implement Phase 4 Step A stateless authentication and authorization

  - V5__users.sql: users table with case-insensitive unique email index and owner_user_id on accounts
  - Add approved Spring Security 7 & OAuth2 Resource Server starters from Boot BOM
  - JwtService: issue HS256 tokens with fail-fast 32-byte secret check and strict validation
  - PasswordPolicy: enforce >= 12 chars and <= 72 UTF-8 bytes (BCrypt truncation limit)
  - AuthService: timing-attack-resistant login with dummy BCrypt comparison for unknown emails
  - AdminBootstrapService: bootstrap initial admin account on startup without logging passwords
  - AccountOwnershipPolicy: conceal peer accounts, statements, and transfers behind 404 Not Found
  - SecurityConfig: deny-by-default, permitAll on /register, /login, /health, ROLE_ADMIN on /admin/**
  - AuthSecurityIntegrationTest, SelfServiceDepositsIntegrationTest, JwtSecretStartupFailureTest: 29 new security tests
  - Update RestApiIntegrationTest with auth tokens (130 total tests passing, 0 failures, 0 errors)
  - docs/API.md, docs/SECURITY.md, docs/api-examples.http: updated and authored documentation
  ```

- **Next step proposed:**
  Phase 4, Step B (Token refresh, logout / token revocation, or rate limiting at API gateway / application level).
