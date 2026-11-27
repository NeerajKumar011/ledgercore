# Phase 2C Consecutive Test Runs Evidence

Three consecutive executions of the full test suite (`mvnw.cmd test` without `JAVA_TOOL_OPTIONS`).

---

## Run 1

- **Timestamp:** 2026-11-23T19:12:58+05:30
- **Git Status:**
```
 M docs/AGENT_REPORT.md
 M docs/AGENT_RULES.md
 M src/main/java/com/ledgercore/ledger/LedgerEntryRepository.java
 M src/main/java/com/ledgercore/transfer/TransferService.java
 M src/main/resources/application.properties
 M src/test/java/com/ledgercore/TestcontainersConfiguration.java
 M src/test/java/com/ledgercore/transfer/TransferConcurrencyIntegrationTest.java
 M src/test/java/com/ledgercore/transfer/TransferServiceUnitTest.java
?? docs/API.md
?? docs/api-examples.http
?? src/main/java/com/ledgercore/account/AccountQueryService.java
?? src/main/java/com/ledgercore/api/
?? src/main/java/com/ledgercore/transfer/TransferNotFoundException.java
?? src/main/java/com/ledgercore/transfer/TransferQueryService.java
?? src/main/resources/db/migration/V4__ledger_statement_index.sql
?? src/test/java/com/ledgercore/api/
?? src/test/java/com/ledgercore/transfer/StaleEntityPitfallIntegrationTest.java
?? src/test/java/com/ledgercore/transfer/TransferLockTimeoutIntegrationTest.java
```
- **Benchmark Log Lines (from `TransferConcurrencyIntegrationTest`):**
```
2026-11-23T13:44:09.336Z  INFO 19608 --- [ledgercore] [           main] c.l.t.TransferConcurrencyIntegrationTest : === Random Transfers Benchmark Summary ===
2026-11-23T13:44:09.336Z  INFO 19608 --- [ledgercore] [           main] c.l.t.TransferConcurrencyIntegrationTest : Total Transfers: 500, Completed: 500, Business Failures: 0
2026-11-23T13:44:09.337Z  INFO 19608 --- [ledgercore] [           main] c.l.t.TransferConcurrencyIntegrationTest : Wall Time: 2406 ms, Throughput: 207.81 tx/sec
2026-11-23T13:44:09.337Z  INFO 19608 --- [ledgercore] [           main] c.l.t.TransferConcurrencyIntegrationTest : Latency: p50=123 ms, p95=349 ms, max=752 ms
```
- **Raw Summary Block:**
```
[INFO] Results:
[INFO] 
[INFO] Tests run: 101, Failures: 0, Errors: 0, Skipped: 0
[INFO] 
[INFO] ------------------------------------------------------------------------
[INFO] BUILD SUCCESS
[INFO] ------------------------------------------------------------------------
[INFO] Total time:  01:16 min
[INFO] Finished at: 2026-11-23T19:14:18+05:30
[INFO] ------------------------------------------------------------------------
```

---

## Run 2

- **Timestamp:** 2026-11-23T19:14:46+05:30
- **Git Status:**
```
 M docs/AGENT_REPORT.md
 M docs/AGENT_RULES.md
 M src/main/java/com/ledgercore/ledger/LedgerEntryRepository.java
 M src/main/java/com/ledgercore/transfer/TransferService.java
 M src/main/resources/application.properties
 M src/test/java/com/ledgercore/TestcontainersConfiguration.java
 M src/test/java/com/ledgercore/transfer/TransferConcurrencyIntegrationTest.java
 M src/test/java/com/ledgercore/transfer/TransferServiceUnitTest.java
?? docs/API.md
?? docs/api-examples.http
?? src/main/java/com/ledgercore/account/AccountQueryService.java
?? src/main/java/com/ledgercore/api/
?? src/main/java/com/ledgercore/transfer/TransferNotFoundException.java
?? src/main/java/com/ledgercore/transfer/TransferQueryService.java
?? src/main/resources/db/migration/V4__ledger_statement_index.sql
?? src/test/java/com/ledgercore/api/
?? src/test/java/com/ledgercore/transfer/StaleEntityPitfallIntegrationTest.java
?? src/test/java/com/ledgercore/transfer/TransferLockTimeoutIntegrationTest.java
```
- **Benchmark Log Lines (from `TransferConcurrencyIntegrationTest`):**
```
2026-11-23T13:45:50.779Z  INFO 23256 --- [ledgercore] [           main] c.l.t.TransferConcurrencyIntegrationTest : === Random Transfers Benchmark Summary ===
2026-11-23T13:45:50.780Z  INFO 23256 --- [ledgercore] [           main] c.l.t.TransferConcurrencyIntegrationTest : Total Transfers: 500, Completed: 500, Business Failures: 0
2026-11-23T13:45:50.780Z  INFO 23256 --- [ledgercore] [           main] c.l.t.TransferConcurrencyIntegrationTest : Wall Time: 1880 ms, Throughput: 265.96 tx/sec
2026-11-23T13:45:50.781Z  INFO 23256 --- [ledgercore] [           main] c.l.t.TransferConcurrencyIntegrationTest : Latency: p50=99 ms, p95=246 ms, max=526 ms
```
- **Raw Summary Block:**
```
[INFO] Results:
[INFO] 
[INFO] Tests run: 101, Failures: 0, Errors: 0, Skipped: 0
[INFO] 
[INFO] ------------------------------------------------------------------------
[INFO] BUILD SUCCESS
[INFO] ------------------------------------------------------------------------
[INFO] Total time:  01:09 min
[INFO] Finished at: 2026-11-23T19:16:00+05:30
[INFO] ------------------------------------------------------------------------
```

---

## Run 3

- **Timestamp:** 2026-11-23T19:16:16+05:30
- **Git Status:**
```
 M docs/AGENT_REPORT.md
 M docs/AGENT_RULES.md
 M src/main/java/com/ledgercore/ledger/LedgerEntryRepository.java
 M src/main/java/com/ledgercore/transfer/TransferService.java
 M src/main/resources/application.properties
 M src/test/java/com/ledgercore/TestcontainersConfiguration.java
 M src/test/java/com/ledgercore/transfer/TransferConcurrencyIntegrationTest.java
 M src/test/java/com/ledgercore/transfer/TransferServiceUnitTest.java
?? docs/API.md
?? docs/api-examples.http
?? src/main/java/com/ledgercore/account/AccountQueryService.java
?? src/main/java/com/ledgercore/api/
?? src/main/java/com/ledgercore/transfer/TransferNotFoundException.java
?? src/main/java/com/ledgercore/transfer/TransferQueryService.java
?? src/main/resources/db/migration/V4__ledger_statement_index.sql
?? src/test/java/com/ledgercore/api/
?? src/test/java/com/ledgercore/transfer/StaleEntityPitfallIntegrationTest.java
?? src/test/java/com/ledgercore/transfer/TransferLockTimeoutIntegrationTest.java
```
- **Benchmark Log Lines (from `TransferConcurrencyIntegrationTest`):**
```
2026-11-23T13:47:23.152Z  INFO 13348 --- [ledgercore] [           main] c.l.t.TransferConcurrencyIntegrationTest : === Random Transfers Benchmark Summary ===
2026-11-23T13:47:23.152Z  INFO 13348 --- [ledgercore] [           main] c.l.t.TransferConcurrencyIntegrationTest : Total Transfers: 500, Completed: 500, Business Failures: 0
2026-11-23T13:47:23.153Z  INFO 13348 --- [ledgercore] [           main] c.l.t.TransferConcurrencyIntegrationTest : Wall Time: 1997 ms, Throughput: 250.38 tx/sec
2026-11-23T13:47:23.154Z  INFO 13348 --- [ledgercore] [           main] c.l.t.TransferConcurrencyIntegrationTest : Latency: p50=100 ms, p95=276 ms, max=524 ms
```
- **Raw Summary Block:**
```
[INFO] Results:
[INFO] 
[INFO] Tests run: 101, Failures: 0, Errors: 0, Skipped: 0
[INFO] 
[INFO] ------------------------------------------------------------------------
[INFO] BUILD SUCCESS
[INFO] ------------------------------------------------------------------------
[INFO] Total time:  01:12 min
[INFO] Finished at: 2026-11-23T19:17:32+05:30
[INFO] ------------------------------------------------------------------------
```
