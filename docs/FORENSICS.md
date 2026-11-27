# LedgerCore Audit Log Forensics & Verification Guide

This guide describes how an investigator or security auditor inspects, verifies, and analyzes the tamper-evident audit log in LedgerCore.

---

## 1. Audit Log Architecture Overview

LedgerCore records security and financial state transitions in the immutable `audit_events` table using cryptographic hash chaining:
- Every event is assigned a monotonically increasing 64-bit sequence number (`seq`, starting at 1).
- Event sequence assignment is strictly serialized under a transactional PostgreSQL advisory lock (`pg_advisory_xact_lock(8492049102941029384)`), preventing sequence gaps or race conditions across concurrent transactions.
- Each event includes the SHA-256 hash of the immediately preceding event (`prev_hash`).
- For the genesis event (`seq = 1`), `prev_hash` is 64 hex zeros (`0000000000000000000000000000000000000000000000000000000000000000`).
- The row's own hash (`row_hash`) is computed as:
  $$\text{row\_hash} = \text{SHA-256}(\text{canonical\_representation})$$

### Canonical Representation Format
The canonical string format enforces deterministic serialization:
```text
seq|id|occurred_at_micros|actor_user_id|actor_role|action|outcome|target_type|target_id|request_id|client_ip|canonical_details_json|prev_hash
```
Rules:
1. `occurred_at_micros`: UTC ISO-8601 timestamp formatted with microsecond precision (`YYYY-MM-DDTHH:mm:ss.SSSSSSZ`). Nanoseconds are truncated to microseconds to match PostgreSQL `timestamptz` storage.
2. Missing or null nullable fields (`actor_user_id`, `actor_role`, `target_type`, `target_id`, `request_id`, `client_ip`) are represented as empty strings between pipes (`||`).
3. `details`: Deterministically serialized JSON where all object keys are sorted lexicographically at every nesting level. Null or empty details format as `{}`.
4. All text fields and hashes use UTF-8 encoding.

---

## 2. In-Database Immutability Protections

The `audit_events` table is protected at the database engine level via PostgreSQL triggers defined in `V7__audit_log.sql`:
1. **Row-level trigger (`audit_events_prevent_update_or_delete`)**: Intercepts `BEFORE UPDATE OR DELETE` on any row and raises exception `IMMUTABLE_AUDIT_LOG: audit_events rows cannot be updated or deleted`.
2. **Statement-level trigger (`audit_events_prevent_truncate`)**: Intercepts `BEFORE TRUNCATE` on `audit_events` and raises exception `IMMUTABLE_AUDIT_LOG: audit_events table cannot be truncated`.

In production environments, the application database role must NOT have `SUPERUSER` or `CREATEROLE` privileges, preventing `ALTER TABLE ... DISABLE TRIGGER` attacks.

---

## 3. Verification Procedure

### 3.1 Automated API Verification
Security auditors with the `ADMIN` role can execute cryptographic chain verification via the REST API:

```bash
curl -X GET "http://127.0.0.1:8080/api/v1/admin/audit/verify" \
  -H "Authorization: Bearer <ADMIN_JWT>" \
  -H "Accept: application/json"
```

#### Successful Verification Response (`200 OK`)
```json
{
  "valid": true,
  "verifiedCount": 5420,
  "headSeq": 5420,
  "headHash": "d2b456...64hex",
  "firstSeq": 1,
  "firstHash": "8a31b2...64hex",
  "verificationDurationMs": 142
}
```

#### Tamper Detection Response (`200 OK`)
If any record was modified, deleted, or inserted out-of-order:
```json
{
  "valid": false,
  "verifiedCount": 104,
  "headSeq": 500,
  "headHash": "...",
  "failureSeq": 105,
  "failureId": "550e8400-e29b-41d4-a716-446655440000",
  "failureReason": "PREV_HASH_MISMATCH: row prev_hash does not match previous row hash",
  "expectedHash": "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
  "actualHash": "0000000000000000000000000000000000000000000000000000000000000000",
  "verificationDurationMs": 48
}
```

### 3.2 Inspecting Current Log Head
To retrieve the latest sequence number and hash for comparison against external anchors:

```bash
curl -X GET "http://127.0.0.1:8080/api/v1/admin/audit/head" \
  -H "Authorization: Bearer <ADMIN_JWT>"
```

Response:
```json
{
  "seq": 5420,
  "id": "78e1ab7d-943e-4e65-8a90-c328f5d3e17f",
  "occurredAt": "2026-11-28T00:15:30.123456Z",
  "rowHash": "d2b456...",
  "prevHash": "c1a345..."
}
```

### 3.3 Querying Filtered Audit Records
To paginate or filter audit events for a specific actor or target:

```bash
curl -X GET "http://127.0.0.1:8080/api/v1/admin/audit?action=TRANSFER_MADE&limit=50" \
  -H "Authorization: Bearer <ADMIN_JWT>"
```

Supported query parameters:
- `actorUserId`: UUID filter.
- `action`: Audit action string (e.g. `LOGIN_FAILURE`, `TRANSFER_MADE`).
- `outcome`: `SUCCESS` or `FAILURE`.
- `from`: ISO-8601 UTC timestamp lower bound.
- `to`: ISO-8601 UTC timestamp upper bound.
- `limit`: Page size (default 50, max 200).
- `seqAfter`: Cursor for keyset pagination (`seq > seqAfter`).

---

## 4. Threat Model: What Hash Chaining Detects vs. Does NOT Detect

### What the Chain Detects
1. **Single-Row Alteration**: Modifying any field (amount, actor, action, timestamp) changes its canonical SHA-256 hash. The stored `row_hash` will fail validation.
2. **Hash Forgery on Single Row**: If an attacker recomputes `row_hash` to match altered row data, the next record (`seq + 1`) contains the original hash in its `prev_hash`. The chain breaks at `seq + 1`.
3. **Row Deletion**: Deleting any row causes a sequence gap (`seq != prev_seq + 1`) and a `prev_hash` mismatch on the successor row.
4. **Row Insertion / Splicing**: Injecting a row between two existing rows breaks sequence continuity and `prev_hash` links.
5. **Partial Truncation (Tail Deletion)**: If an attacker truncates the most recent $N$ records, the internal chain from $1$ to $M$ may verify as internally consistent. However, comparing the log head against an **external anchor** immediately exposes the rollback.

### What the Chain Alone Does NOT Detect (Without External Anchoring)
- **Log Rollback (Tail Truncation)**: If an attacker with superuser privileges deletes the last 100 events and modifies the database, internal verification of events $1 \dots (N-100)$ passes.
  - **Mitigation: External Anchoring**: Head hashes (`seq` and `row_hash`) must be periodically published to an append-only external destination (such as AWS S3 Object Lock in compliance mode, Git commits, or an external transparency log). Any rollback is caught by comparing `head` against the anchor.
- **Whole-Table Recreation**: If an attacker recreates the entire table from scratch with alternate data and valid re-hashed entries.
  - **Mitigation**: Cross-referencing against external anchors and financial journal entries in the double-entry `ledger_entries` table.

---

## 5. Security & Operational Checklist for Investigators

- [ ] Confirm `audit_events_prevent_update_or_delete` trigger is `ENABLED` on PostgreSQL.
- [ ] Confirm `audit_events_prevent_truncate` trigger is `ENABLED` on PostgreSQL.
- [ ] Confirm application database user (`ledger_user`) does not possess `SUPERUSER` or `CREATEROLE` permissions.
- [ ] Run automated `/api/v1/admin/audit/verify` verification.
- [ ] Verify `headSeq` and `headHash` match the latest off-site external anchor.
- [ ] Verify no secrets, plain passwords, or raw tokens appear in the `details` JSON payload.
