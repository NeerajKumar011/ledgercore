-- =============================================================================
-- V2__ledger_hardening.sql
-- LedgerCore ledger hardening migration
-- PostgreSQL 18 syntax; Flyway runs this exactly once, after V1.
-- =============================================================================

-- ---------------------------------------------------------------------------
-- 1a. TRUNCATE protection for ledger_entries
--     The existing row-level trigger (V1) fires BEFORE UPDATE OR DELETE FOR
--     EACH ROW; it does NOT fire on TRUNCATE.  This statement-level trigger
--     closes that gap.
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION ledger_entries_no_truncate()
RETURNS TRIGGER
LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION
        'ledger_entries is INSERT-only: TRUNCATE is forbidden';
END;
$$;

-- Fires BEFORE TRUNCATE, statement-level (FOR EACH STATEMENT), so there
-- is no OLD/NEW row; a single RAISE aborts the whole TRUNCATE command.
CREATE TRIGGER ledger_entries_no_truncate
    BEFORE TRUNCATE ON ledger_entries
    FOR EACH STATEMENT EXECUTE FUNCTION ledger_entries_no_truncate();

-- ---------------------------------------------------------------------------
-- 1b. Currency format constraints
--     Ensures the currency column always contains exactly three ASCII
--     upper-case letters (ISO-4217 format), on all three tables.
-- ---------------------------------------------------------------------------
ALTER TABLE accounts
    ADD CONSTRAINT accounts_currency_format_chk
        CHECK (currency ~ '^[A-Z]{3}$');

ALTER TABLE transfers
    ADD CONSTRAINT transfers_currency_format_chk
        CHECK (currency ~ '^[A-Z]{3}$');

ALTER TABLE ledger_entries
    ADD CONSTRAINT ledger_entries_currency_format_chk
        CHECK (currency ~ '^[A-Z]{3}$');

-- ---------------------------------------------------------------------------
-- 1c. Transfer failure_reason consistency
--     failure_reason must be non-null if and only if status = 'FAILED'.
--     The expression (status = 'FAILED') is a boolean; we assert it equals
--     (failure_reason IS NOT NULL), i.e. both sides must be true together.
-- ---------------------------------------------------------------------------
ALTER TABLE transfers
    ADD CONSTRAINT transfers_failure_reason_chk
        CHECK ((status = 'FAILED') = (failure_reason IS NOT NULL));

-- ---------------------------------------------------------------------------
-- 1d. Indexes to support "transfers from / to an account" query patterns
--     (the PK and idempotency_key already have indexes from V1).
-- ---------------------------------------------------------------------------
CREATE INDEX transfers_from_account_id_idx ON transfers (from_account_id);
CREATE INDEX transfers_to_account_id_idx   ON transfers (to_account_id);
