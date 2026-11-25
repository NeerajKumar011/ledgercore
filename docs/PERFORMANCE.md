# LedgerCore Concurrency Benchmark Performance

Observed performance metrics from the concurrency benchmark scenario (`TransferConcurrencyIntegrationTest.randomTransfersAcrossAccounts`) across 3 consecutive runs without `JAVA_TOOL_OPTIONS`.

---

## 1. Observed Performance Metrics (500 Transfers Across 50 Accounts)

| Metric | Run 1 | Run 2 | Run 3 | Overall Observed Range |
|---|---|---|---|---|
| **Wall Clock Time** | 2406 ms | 1880 ms | 1997 ms | 1880 ms – 2406 ms |
| **Throughput (tx/s)** | 207.81 tx/sec | 265.96 tx/sec | 250.38 tx/sec | **207.81 – 265.96 tx/sec** |
| **p50 Latency** | 123 ms | 99 ms | 100 ms | **99 – 123 ms** |
| **p95 Latency** | 349 ms | 246 ms | 276 ms | **246 – 349 ms** |
| **Max Latency** | 752 ms | 526 ms | 524 ms | **524 – 752 ms** |

In all 3 runs:
- Total transfers executed: 500
- Completed successfully: 500
- Business failures: 0
- Unexpected errors or exceptions: 0
- End-of-test reconciliation: 100% consistent (0 problems, total currency balances = 0)

---

## 2. Test Hardware Specifications

- **Processor (CPU):** AMD Ryzen 5 7520U with Radeon Graphics
- **Physical Cores:** 4
- **Logical Processors:** 8
- **Base / Boost Frequency:** ~2.80 GHz / up to 4.30 GHz
- **Installed System Memory (RAM):** 16.0 GB (15.2 GB usable)
- **Host Operating System:** Microsoft Windows 11 Home 64-bit
- **Java Runtime:** OpenJDK 64-Bit Server VM (build 25.0.4.1+1-b258.1, Eclipse Adoptium Temurin)
- **Database Engine:** PostgreSQL 18.7 inside Linux container via Testcontainers / Docker Desktop

---

## 3. Benchmark Caveats & Environmental Constraints

1. **Colocated Environment:** Both the Java Spring Boot application (JVM) and the PostgreSQL 18 database run on the same physical host machine inside Docker Desktop (WSL2 VM), sharing CPU, L3 cache, memory bandwidth, and disk I/O.
2. **Thread Contention vs. Connection Pool:** The benchmark releases 32 concurrent client threads against a HikariCP connection pool configured with a maximum of 20 connections (`spring.datasource.hikari.maximum-pool-size=20`). Because threads exceed available connections (32 threads vs. 20 connections), individual transaction latencies include HikariCP connection lease queue wait time under load.
3. **Simultaneous Load Release:** All 500 tasks are queued and released simultaneously using a `CountDownLatch` start-gate against a cold-to-warm pool, maximizing peak initial connection acquisition latency and thread context switching.
4. **Pessimistic Row Locking & Database Deferred Triggers:** Every transfer executes:
   - Dynamic PostgreSQL transaction configuration: `SELECT set_config('lock_timeout', '3s', true)`
   - Ascending-ordered pessimistic row locks: `SELECT ... FOR UPDATE` on both source and destination account rows
   - Idempotency reservation: `INSERT ... ON CONFLICT DO NOTHING`
   - Two ledger entry inserts with deferred constraint trigger verification: `ledger_entries_journal_balanced_chk` evaluated at transaction commit
5. **Purpose of Benchmark:** This test serves as a **concurrency stress test** to prove data consistency, deadlock prevention, zero double-spending, and race safety under heavy contention. It is **not** a tuned production throughput benchmark claim. Production setups with dedicated database servers, optimized connection pools, and distributed instances would exhibit different latency distributions and throughput characteristics.
