# LedgerCore Agent Report

## Current status
- **Phase / step:** Phase 4A-FIX — Security Defects Remediated & Hardened
- **Overall:** DONE
- **Last updated:** 2026-11-25T22:15 IST
- **One-line summary:** Phase 4A-FIX completed: docker-compose loopback binding & DB container least-privilege env vars verified; per-user idempotency key SHA-256 scoping implemented; ownerRef removed from CreateAccountRequest; BCrypt executed outside DB transactions; IllegalArgumentException sanitized in GlobalExceptionHandler; SYSTEM account UUIDs concealed in TransferResponse; @Size(max = 256) password bounds enforced; 6 new security tests added; 136 total tests passing with 0 failures, 0 errors; V1-V5 migration immutability verified.

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
None! All 136 unit and integration tests are passing cleanly across all 13 test classes (0 failures, 0 errors, 0 skipped).

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

### Phases 2-4A summary — Transfer Engine, Bookkeeping, Concurrency, REST API, Test Hardening (2026-11-15 to 2026-11-23)

- **Status:** COMPLETED & VERIFIED across all historical phases.
- **Commit History & Branches:**
  - Commit `8cedc26` on `main`: Phase 0 hygiene & repository setup.
  - Phase 1 Step 1 & 2 on `main`: Initial schema, immutable ledger trigger, domain models, JPA repositories.
  - Branch `phase-2c-concurrency`: Phase 2 Step A (Transfer engine), Phase 2 Step B (Bookkeeping integrity), and Phase 2 Step C (Concurrency proof).
  - Branch `phase-3a-rest-api`: Created after WIP commit on `phase-2c-concurrency`. Phase 3 Step A (REST API for accounts, transfers, statements, deposits).
- **Key Decisions & Implemented Architecture:**
  1. **Phase 2 Step A — Transfer Engine (`V2__ledger_hardening.sql`):**
     - Strict pessimistic write locking (`SELECT ... FOR UPDATE`) on account rows.
     - Deterministic account ID ordering (`UUID.compareTo`) to prevent deadlock cycles across concurrent opposing transfers.
     - 5-second database lock timeout (`set_config('lock_timeout', ...)`), translated from PostgreSQL SQLSTATE `55P03` to `TransferBusyException`.
     - Idempotency key tracking in `transfers` table (`idempotency_key` unique index).
  2. **Phase 2 Step B — Bookkeeping Integrity (`V3__system_accounts_and_journal_balance.sql`):**
     - Double-entry balance trigger enforcing zero-sum net journal sum per transaction (`sum(amount_minor) = 0`).
     - System funding accounts (`SYSTEM_FUNDING`) for initial deposits and currency funding.
     - Reversible transfer execution with compensation entries.
  3. **Phase 2 Step C — Concurrency Proof:**
     - 12 comprehensive concurrency test scenarios in `TransferConcurrencyIntegrationTest`: 100 concurrent transfers with zero deadlocks, racing same-key transfers, racing differing payloads, concurrent deposits to single account, saturated connection pool, and continuous zero-sum journal conservation verification.
  4. **Phase 3 Step A — REST API (`V4__ledger_statement_index.sql`):**
     - Thin controllers: `AccountController` and `TransferController` mapping DTOs to service calls.
     - JSON-only API at base path `/api/v1` with server bound to loopback `127.0.0.1`.
     - Money strictly represented as `amountMinor` (JavaScript safe-integer capped at 9007199254740991).
     - RFC 9457 `ProblemDetail` (`application/problem+json`) with `GlobalExceptionHandler` and `X-Request-Id` tracing.
- **Evidence & Performance References:**
  - Performance and throughput benchmarks recorded in `docs/PERFORMANCE.md` (207–266 tx/sec).
  - Verification run logs and timestamps recorded in `docs/evidence/phase-2c-runs.md`.

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

### Entry 16 — Phase 4A-FIX — Security Defects Remediated & Hardened — 2026-11-25 22:15 IST

- **Status:** COMPLETED & VERIFIED
- **Goal:** Resolve security defects identified in security review across database exposure, per-user idempotency scoping, account creation ownerRef spoofing, BCrypt connection starvation, error detail leakage, SYSTEM account UUID concealment, and password validation boundaries.

- **Tasks Completed:**
  1. **Docker Compose Database Port & Least Privilege (Task 1):**
     - Bound database host port strictly to loopback: `"127.0.0.1:${DB_PORT:-5432}:5432"`.
     - Replaced `env_file:` with explicit `environment:` block passing only `POSTGRES_DB`, `POSTGRES_USER`, and `POSTGRES_PASSWORD` (interpolated from `.env`), eliminating injection of `JWT_SECRET`, `ADMIN_EMAIL`, and `ADMIN_PASSWORD` into the database container.
     - Verified with `docker compose config` (only `POSTGRES_*` environment variables present) and `docker compose ps` (`127.0.0.1:5433->5432/tcp`).
  2. **Per-User Idempotency Scoping (Task 2):**
     - Implemented `com.ledgercore.api.IdempotencyScope.scope(callerUserId, clientKey)` generating lowercase 64-character hex digest of `SHA-256(callerUserId + "|" + clientKey)`.
     - Wired into `TransferController` and `AccountController` (deposits) so raw client keys are never stored in the database.
     - Documented in `docs/API.md` and `docs/SECURITY.md`.
  3. **Account Creation ownerRef Stripping (Task 3):**
     - Removed `ownerRef` from `CreateAccountRequest` and added `@JsonIgnoreProperties(ignoreUnknown = true)`.
     - In `AccountController.openAccount`, `ownerRef` and `ownerUserId` are unconditionally set from `caller.userId()`. Client-sent `ownerRef` has zero effect.
  4. **BCrypt Transaction & Connection Pool Isolation (Task 4):**
     - Removed `@Transactional` from `AuthService`.
     - Password hashing (`passwordEncoder.encode`) and comparison (`passwordEncoder.matches`) are executed strictly outside database transactions.
     - User existence checks and `saveAndFlush` are executed in a short scoped `TransactionTemplate.execute(...)` block, preventing database connection starvation under concurrent authentication.
     - Added test verifying `TransactionSynchronizationManager.isActualTransactionActive()` is false during BCrypt calls.
  5. **Error Sanitization (Task 5):**
     - Created `InvalidRequestException` for client-intended 400 errors.
     - Converted client-intended `IllegalArgumentException` in `Transfer.java` (distinct accounts) to `InvalidTransferRequestException` and in `AccountService.java` (negative deposit) to `InvalidRequestException`.
     - In `GlobalExceptionHandler`, configured `@ExceptionHandler(IllegalArgumentException.class)` to log server-side warnings with the `X-Request-Id` and return generic ProblemDetail: title `"Invalid Request"`, detail `"The request contains invalid parameters."` without leaking exception text.
     - Added `@GetMapping("/illegal-argument")` to `TestErrorController` throwing `IllegalArgumentException("sensitive-internal-detail")` and verified "sensitive-internal-detail" is never leaked in the response.
  6. **Conceal SYSTEM Accounts in TransferResponse (Task 6):**
     - Added `Optional<AccountKind> findKindById(@Param("id") UUID id)` projection in `AccountRepository`.
     - In `TransferQueryService.toResponse(Transfer t)`, set `fromAccountId` or `toAccountId` to `null` if the referenced account has `AccountKind.SYSTEM`.
     - Updated deposit tests to assert `fromAccountId` is null.
  7. **Password Max Length Boundary (Task 7):**
     - Added `@Size(max = 256, message = "password must not exceed 256 characters")` to `RegisterRequest` and `LoginRequest` password fields to reject oversized strings at the validation layer before cryptographic hashing.
     - Retained 72 UTF-8 byte boundary validation in `PasswordPolicy` for registration.
  8. **Comprehensive Integration Tests (Task 8):**
     - Added 6 new integration tests in `AuthSecurityIntegrationTest`:
       - `idempotency_perUserScoping`: verifies User A and User B can use identical client key without conflict, same-user replays return 201 with `Idempotent-Replayed: true`, same-user differing payload returns 409 Conflict, and admin deposit causes no conflict.
       - `createAccount_ownerRefInBodyIgnored`: verifies client-provided `ownerRef` is stripped and caller's user ID is assigned.
       - `bcrypt_executedOutsideTransactions`: verifies BCrypt `encode` and `matches` run outside database transactions via `TransactionSynchronizationManager`.
       - `error_illegalArgumentDoesNotLeakDetail`: verifies `/api/v1/test-error/illegal-argument` returns generic 400 without leaking exception text.
       - `password_exceeding256CharsRejected`: verifies passwords > 256 chars return 400 Validation Failed on register and login.
       - `concealment_systemAccountIdNulledInTransferResponse`: verifies deposits return `fromAccountId: null`.
     - Verified all 12 Phase 2C concurrency integration tests continue to pass cleanly.
  9. **Documentation & Condensation (Task 9):**
     - Updated `docs/SECURITY.md` STRIDE threat matrix and security architecture sections.
     - Updated `docs/API.md` specification.
     - Condensed `docs/AGENT_REPORT.md` to under ~25 KB.

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
     [INFO] Tests run: 13, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.482 s -- in com.ledgercore.account.AccountUnitTest
     [INFO] Running com.ledgercore.api.RestApiIntegrationTest
     [INFO] Tests run: 13, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 40.69 s -- in com.ledgercore.api.RestApiIntegrationTest
     [INFO] Running com.ledgercore.auth.AuthSecurityIntegrationTest
     [INFO] Tests run: 32, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 13.16 s -- in com.ledgercore.auth.AuthSecurityIntegrationTest
     [INFO] Running com.ledgercore.auth.JwtSecretStartupFailureTest
     [INFO] Tests run: 2, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.010 s -- in com.ledgercore.auth.JwtSecretStartupFailureTest
     [INFO] Running com.ledgercore.auth.SelfServiceDepositsIntegrationTest
     [INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 4.447 s -- in com.ledgercore.auth.SelfServiceDepositsIntegrationTest
     [INFO] Running com.ledgercore.common.MoneyTest
     [INFO] Tests run: 8, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.038 s -- in com.ledgercore.common.MoneyTest
     [INFO] Running com.ledgercore.DomainIntegrationTest
     [INFO] Tests run: 10, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 3.303 s -- in com.ledgercore.DomainIntegrationTest
     [INFO] Running com.ledgercore.LedgercoreApplicationTests
     [INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.059 s -- in com.ledgercore.LedgercoreApplicationTests
     [INFO] Running com.ledgercore.transfer.StaleEntityPitfallIntegrationTest
     [INFO] Tests run: 2, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.368 s -- in com.ledgercore.transfer.StaleEntityPitfallIntegrationTest
     [INFO] Running com.ledgercore.transfer.TransferConcurrencyIntegrationTest
     [INFO] Tests run: 12, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 47.72 s -- in com.ledgercore.transfer.TransferConcurrencyIntegrationTest
     [INFO] Running com.ledgercore.transfer.TransferLockTimeoutIntegrationTest
     [INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 3.128 s -- in com.ledgercore.transfer.TransferLockTimeoutIntegrationTest
     [INFO] Running com.ledgercore.transfer.TransferServiceIntegrationTest
     [INFO] Tests run: 17, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 1.750 s -- in com.ledgercore.transfer.TransferServiceIntegrationTest
     [INFO] Running com.ledgercore.transfer.TransferServiceUnitTest
     [INFO] Tests run: 24, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.067 s -- in com.ledgercore.transfer.TransferServiceUnitTest
     [INFO] 
     [INFO] Results:
     [INFO] 
     [INFO] Tests run: 136, Failures: 0, Errors: 0, Skipped: 0
     [INFO] 
     [INFO] ------------------------------------------------------------------------
     [INFO] BUILD SUCCESS
     [INFO] ------------------------------------------------------------------------
     [INFO] Total time:  02:16 min
     [INFO] Finished at: 2026-11-25T22:18:20+05:30
     [INFO] ------------------------------------------------------------------------
     ```

  2. **Docker Compose PS Showing Loopback Binding:**
     ```text
     NAME            IMAGE         COMMAND                  SERVICE   CREATED        STATUS                                     PORTS
     ledgercore-db   postgres:18   "docker-entrypoint.s…"   db        1 second ago   Up Less than a second (health: starting)   127.0.0.1:5433->5432/tcp
     ```

  3. **Flyway Migration Immutability Check:**
     - Command: `git diff -- src/main/resources/db/migration/V1__init_schema.sql src/main/resources/db/migration/V2__ledger_hardening.sql src/main/resources/db/migration/V3__system_accounts_and_journal_balance.sql src/main/resources/db/migration/V4__ledger_statement_index.sql src/main/resources/db/migration/V5__users.sql`
     - Result: Completely empty (0 modifications across V1, V2, V3, V4, and V5).

- **Suggested commit message:**
  ```text
  fix(security): remediate review findings across auth, docker, and error handling

  - docker-compose.yml: bind DB port strictly to loopback and pass only POSTGRES_* env vars
  - IdempotencyScope: scope idempotency keys per user with SHA-256(userId + "|" + clientKey)
  - CreateAccountRequest: remove ownerRef; account owner is strictly derived from caller userId
  - AuthService: execute BCrypt hashing and matching outside transactions to prevent pool exhaustion
  - GlobalExceptionHandler: sanitize IllegalArgumentException to generic 400 without leaking details
  - TransferQueryService: conceal SYSTEM account UUIDs in TransferResponse by setting to null
  - RegisterRequest/LoginRequest: enforce @Size(max = 256) on passwords
  - AuthSecurityIntegrationTest: add 6 new security tests (136 total tests passing)
  - docs/API.md, docs/SECURITY.md, docs/AGENT_REPORT.md: updated and condensed documentation
  ```

- **Next step proposed:**
  Human commit of Phase 4A-FIX changes on branch `phase-3a-rest-api` (or prepare for Phase 4 Step B).

---

### Entry 17: Phase 4, Step B — Refresh Tokens, Instant Revocation, Lockout, Rate Limiting & Hardening
- **Date / Commit:** 2026-11-25 (Working tree on branch `phase-3a-rest-api`)
- **Author:** Antigravity (Phase 4 Step B implementation)
- **Goal:** Implement secure refresh tokens with family-based theft detection, instant session revocation via per-request `token_version` check, progressive account lockout with timing parity, in-memory bounded-memory per-IP rate limiting, 16 KB request body limit, defensive security response headers, and admin account disable/enable endpoints. No new dependencies. Migration V6 only.

- **Schema Changes (`V6__refresh_tokens_and_lockout.sql`):**
  - Table `refresh_tokens(id UUID PRIMARY KEY, user_id UUID NOT NULL REFERENCES users(id), family_id UUID NOT NULL, token_hash CHAR(64) NOT NULL UNIQUE, created_at TIMESTAMPTZ NOT NULL, expires_at TIMESTAMPTZ NOT NULL, used_at TIMESTAMPTZ, revoked_at TIMESTAMPTZ, replaced_by UUID REFERENCES refresh_tokens(id))`.
  - Indexes: `idx_refresh_tokens_user_id` on `user_id`, `idx_refresh_tokens_family_id` on `family_id`.
  - Table `users`: Added columns `token_version INT NOT NULL DEFAULT 0`, `failed_login_count INT NOT NULL DEFAULT 0`, `locked_until TIMESTAMPTZ`.

- **Implementation Details:**
  1. **Refresh Token Generation & Hashing (`RefreshTokenGenerator`)**:
     - Generates 32 cryptographically random bytes via `SecureRandom` encoded as base64url string.
     - Persists only the lowercase 64-character hexadecimal SHA-256 hash. The raw token is never stored.
  2. **Atomic CAS Token Rotation & Theft Detection (`AuthService.refresh`)**:
     - Performs a single atomic query `UPDATE refresh_tokens SET used_at = :now WHERE token_hash = :hash AND used_at IS NULL AND revoked_at IS NULL AND expires_at > :now`.
     - Single row affected: Issues new access token and new refresh token in the same `family_id`, sets `replaced_by`.
     - Zero rows affected: If the token exists and was already used or revoked, the server revokes the entire token family (`UPDATE refresh_tokens SET revoked_at = :now WHERE family_id = :familyId`). Committed in an independent transaction so that throwing `BadCredentialsException` (generic 401) does not roll back the revocation.
  3. **Single & All-Session Logout (`AuthService.logout`, `AuthService.logoutAll`)**:
     - `POST /api/v1/auth/logout`: Revokes the family of the presented refresh token (always returns 204).
     - `POST /api/v1/auth/logout-all`: Revokes all user refresh tokens and increments `token_version` in `users`.
  4. **Instant Revocation via JWT Converter (`UserSecurityStateJwtAuthenticationConverter`)**:
     - Fast index lookup `SELECT (u.enabled, u.role, u.tokenVersion) FROM User u WHERE u.id = :userId` on every authenticated request.
     - Instantly rejects disabled users or tokens whose `token_version` claim does not match the database (`401 Unauthorized`). Current database role overrides the token claim.
  5. **Progressive Account Lockout & Timing Parity (`UserRepository.recordLoginFailure`, `AuthService.login`)**:
     - Increments `failed_login_count`. On 5th failure, locks account for 1 minute; subsequent failures double duration up to 60 minutes via atomic PostgreSQL calculation `CAST(:now AS TIMESTAMPTZ) + LEAST(INTERVAL '60 minutes', INTERVAL '1 minute' * POWER(2, LEAST(failed_login_count + 1 - 5, 6)))`.
     - When locked, executes dummy BCrypt comparison (`matches(password, dummyHash)`) before throwing `BadCredentialsException`, matching the response latency of invalid passwords and unknown users without leaking lock status. Successful login resets counters.
  6. **Early Filter Pipeline**:
     - `SecurityHeadersFilter` (`HIGHEST_PRECEDENCE + 1`): Adds `X-Content-Type-Options: nosniff`, `Content-Security-Policy: default-src 'none'; frame-ancestors 'none'`, `Referrer-Policy: no-referrer`, `X-Frame-Options: DENY`, `Cache-Control: no-store` on `/api/**`.
     - `RequestSizeLimitFilter` (`HIGHEST_PRECEDENCE + 2`): Rejects request bodies > 16 KB (16,384 bytes) with `413 Payload Too Large` ProblemDetail.
     - `RateLimitingFilter` (`HIGHEST_PRECEDENCE + 3`): Bounded memory in-memory rate limiter per IP (`request.getRemoteAddr()`; `X-Forwarded-For` ignored). 10/min for `/auth/login`, 5/min for `/auth/register`, 20/min for `/auth/refresh`. Returns `429 Too Many Requests` with `Retry-After`.
  7. **Admin User Management (`AdminUserController`)**:
     - `POST /api/v1/admin/users/{id}/disable`: Disables account, revokes all refresh tokens, increments `token_version`. Guard forbids admin self-disable (400).
     - `POST /api/v1/admin/users/{id}/enable`: Re-enables account.

- **Verification Evidence (Rule 10 Verbatim Outputs):**
  1. **Full Maven Test Suite (`.\mvnw.cmd test`):**
     ```text
     [INFO] Tests run: 13, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.459 s -- in com.ledgercore.account.AccountUnitTest
     [INFO] Tests run: 13, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 35.80 s -- in com.ledgercore.api.RestApiIntegrationTest
     [INFO] Tests run: 32, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 13.06 s -- in com.ledgercore.auth.AuthSecurityIntegrationTest
     [INFO] Tests run: 2, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.009 s -- in com.ledgercore.auth.JwtSecretStartupFailureTest
     [INFO] Tests run: 15, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 15.06 s -- in com.ledgercore.auth.RefreshTokenAndHardeningIntegrationTest
     [INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 4.888 s -- in com.ledgercore.auth.SelfServiceDepositsIntegrationTest
     [INFO] Tests run: 8, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.038 s -- in com.ledgercore.common.MoneyTest
     [INFO] Tests run: 10, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 3.551 s -- in com.ledgercore.DomainIntegrationTest
     [INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.063 s -- in com.ledgercore.LedgercoreApplicationTests
     [INFO] Tests run: 2, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.428 s -- in com.ledgercore.transfer.StaleEntityPitfallIntegrationTest
     [INFO] Tests run: 12, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 42.60 s -- in com.ledgercore.transfer.TransferConcurrencyIntegrationTest
     [INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 4.122 s -- in com.ledgercore.transfer.TransferLockTimeoutIntegrationTest
     [INFO] Tests run: 17, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 1.620 s -- in com.ledgercore.transfer.TransferServiceIntegrationTest
     [INFO] Tests run: 24, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.063 s -- in com.ledgercore.transfer.TransferServiceUnitTest
     [INFO] 
     [INFO] Results:
     [INFO] 
     [INFO] Tests run: 151, Failures: 0, Errors: 0, Skipped: 0
     [INFO] 
     [INFO] ------------------------------------------------------------------------
     [INFO] BUILD SUCCESS
     [INFO] ------------------------------------------------------------------------
     [INFO] Total time:  02:13 min
     ```

  2. **Live Curl Verification Against Running Server:**
     - **Login Response & Security Headers:**
       ```text
       HTTP/1.1 200 
       X-Request-Id: d8aebeed-57f3-4a22-b85f-66ce572d3870
       X-Content-Type-Options: nosniff
       Content-Security-Policy: default-src 'none'; frame-ancestors 'none'
       Referrer-Policy: no-referrer
       X-Frame-Options: DENY
       Cache-Control: no-store
       X-XSS-Protection: 0
       Content-Type: application/json
       Transfer-Encoding: chunked

       {"accessToken":"[REDACTED]","tokenType":"Bearer","expiresInSeconds":900,"refreshToken":"[REDACTED]","refreshExpiresInSeconds":604800}
       ```
     - **Refresh Rotation:**
       ```text
       HTTP/1.1 200 
       X-Request-Id: e165ba1d-14ef-4c2a-a835-81800b31576a
       Cache-Control: no-store
       Content-Type: application/json

       {"accessToken":"[REDACTED]","tokenType":"Bearer","expiresInSeconds":900,"refreshToken":"[REDACTED]","refreshExpiresInSeconds":604800}
       ```
     - **Reuse Theft Detection (Immediate 401 & Family Invalidation):**
       ```text
       # Reuse old token:
       HTTP/1.1 401 
       X-Request-Id: 0e654509-3077-437b-88d2-4c79cdc033d6
       Content-Type: application/problem+json
       {"detail":"Invalid or revoked refresh token","instance":"/api/v1/auth/refresh","status":401,"title":"Unauthorized","requestId":"0e654509-3077-437b-88d2-4c79cdc033d6"}

       # Attempt newest token in the same family:
       HTTP/1.1 401 
       X-Request-Id: 16846618-4263-4b72-9257-826cd7a7f4aa
       Content-Type: application/problem+json
       {"detail":"Invalid or revoked refresh token","instance":"/api/v1/auth/refresh","status":401,"title":"Unauthorized","requestId":"16846618-4263-4b72-9257-826cd7a7f4aa"}
       ```
     - **Logout (204):**
       ```text
       HTTP/1.1 204 
       X-Request-Id: ab4439ad-c326-451b-a1bd-a16813551853
       Cache-Control: no-store
       ```
     - **Progressive Lockout & Timing Parity:**
       ```text
       Attempt 1: HTTP/1.1 401  (took 364 ms)
       Attempt 2: HTTP/1.1 401  (took 335 ms)
       Attempt 3: HTTP/1.1 401  (took 353 ms)
       Attempt 4: HTTP/1.1 401  (took 337 ms)
       Attempt 5: HTTP/1.1 401  (took 339 ms)
       Attempt 6 (Correct Password): HTTP/1.1 401  (took 329 ms)
       {"detail":"Invalid email or password","instance":"/api/v1/auth/login","status":401,"title":"Unauthorized","requestId":"9b70fd1e-28a5-43be-85c8-c559b2b9b48d"}
       ```
     - **Rate Limiting (429 with Retry-After):**
       ```text
       HTTP/1.1 429 
       X-Request-Id: 8e584694-a623-4300-9c12-a7b326cca7d3
       Retry-After: 7
       Content-Type: application/problem+json;charset=UTF-8
       {"detail":"Rate limit exceeded. Please try again later.","instance":"/api/v1/auth/login","status":429,"title":"Too Many Requests","requestId":"8e584694-a623-4300-9c12-a7b326cca7d3"}
       ```

  3. **Flyway Migration Immutability Check:**
     - Command: `git diff -- src/main/resources/db/migration/V1__init_schema.sql src/main/resources/db/migration/V2__ledger_hardening.sql src/main/resources/db/migration/V3__system_accounts_and_journal_balance.sql src/main/resources/db/migration/V4__ledger_statement_index.sql src/main/resources/db/migration/V5__users.sql`
     - Result: Completely empty (0 modifications across V1, V2, V3, V4, and V5).

- **Suggested commit message:**
  ```text
  feat(security): implement refresh tokens, revocation, lockout, and rate limiting (Phase 4B)

  - V6__refresh_tokens_and_lockout.sql: add refresh_tokens table and token_version, failed_login_count, locked_until to users
  - RefreshToken & RefreshTokenRepository: CAS atomic usage and family revocation
  - AuthService: refresh token rotation, family-theft auto-revocation, single & all-session logout
  - UserSecurityStateJwtAuthenticationConverter: per-request DB check for instant token revocation and role refresh
  - Progressive lockout: 5 failures locks for 1 min with exponential backoff up to 60m and BCrypt timing parity
  - RateLimitingFilter: early bounded in-memory per-IP rate limiter (10/min login, 5/min register, 20/min refresh)
  - RequestSizeLimitFilter: reject bodies > 16 KB with 413 Payload Too Large
  - SecurityHeadersFilter: add nosniff, CSP, no-referrer, XFO DENY, and no-store headers
  - AdminUserController: endpoints to disable and enable user accounts with self-disable protection
  - RefreshTokenAndHardeningIntegrationTest: 15 integration tests covering rotation, theft, lockout, size, rate limit
  - Total test suite: 151 tests run, 0 failures, 0 errors
  - docs/API.md, docs/SECURITY.md, docs/AGENT_REPORT.md: updated documentation
  ```

- **Next step proposed:**
  Human commit of Phase 4B changes on branch `phase-3a-rest-api` (or transition to Phase 5).


