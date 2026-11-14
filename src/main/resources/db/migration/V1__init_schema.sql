-- =============================================================================
-- V1__init_schema.sql
-- LedgerCore initial schema
-- PostgreSQL 18 syntax; Flyway runs this exactly once.
-- All monetary values are stored as BIGINT (minor currency units, e.g. cents).
-- All timestamps are TIMESTAMPTZ (UTC-normalised on write).
-- Primary keys use gen_random_uuid() — available without pgcrypto in PG 13+.
-- =============================================================================

-- ---------------------------------------------------------------------------
-- accounts
-- Represents a customer account with a balance expressed in minor currency units.
-- ---------------------------------------------------------------------------
CREATE TABLE accounts (
    id             UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    -- Human-readable account number assigned by the application; must be unique.
    account_number VARCHAR(64) NOT NULL UNIQUE,
    -- Opaque reference to the external customer/owner identity system.
    owner_ref      VARCHAR(255) NOT NULL,
    -- ISO-4217 three-letter currency code, e.g. "USD".
    currency       CHAR(3)     NOT NULL,
    -- Lifecycle state; only ACTIVE accounts can participate in transfers.
    status         VARCHAR(10) NOT NULL DEFAULT 'ACTIVE'
                   CONSTRAINT accounts_status_chk
                   CHECK (status IN ('ACTIVE', 'FROZEN', 'CLOSED')),
    -- Balance in minor units (e.g. cents). Must never go negative.
    balance_minor  BIGINT      NOT NULL DEFAULT 0
                   CONSTRAINT accounts_balance_non_negative_chk
                   CHECK (balance_minor >= 0),
    -- Optimistic-lock version counter; incremented by the application on every update.
    version        BIGINT      NOT NULL DEFAULT 0,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

COMMENT ON TABLE  accounts                     IS 'Customer accounts with currency and balance.';
COMMENT ON COLUMN accounts.balance_minor        IS 'Balance in minor currency units (e.g. cents). Never negative.';
COMMENT ON COLUMN accounts.status               IS 'ACTIVE | FROZEN | CLOSED lifecycle state.';
COMMENT ON COLUMN accounts.version              IS 'Optimistic-lock version; application increments on each write.';

-- ---------------------------------------------------------------------------
-- transfers
-- A single debit-credit pair requested by the application.
-- ---------------------------------------------------------------------------
CREATE TABLE transfers (
    id               UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    -- Caller-supplied idempotency key; duplicate submissions are detected here.
    idempotency_key  VARCHAR(128) NOT NULL UNIQUE,
    -- SHA-256 (or similar) hash of the full transfer request payload for tamper detection.
    request_hash     VARCHAR(255) NOT NULL,
    from_account_id  UUID        NOT NULL
                     REFERENCES accounts(id),
    to_account_id    UUID        NOT NULL
                     REFERENCES accounts(id),
    -- Transfer amount in minor units; must be strictly positive.
    amount_minor     BIGINT      NOT NULL
                     CONSTRAINT transfers_amount_positive_chk
                     CHECK (amount_minor > 0),
    -- Currency must match both accounts (enforced by the application layer).
    currency         CHAR(3)     NOT NULL,
    -- Processing state.
    status           VARCHAR(10) NOT NULL DEFAULT 'PENDING'
                     CONSTRAINT transfers_status_chk
                     CHECK (status IN ('PENDING', 'COMPLETED', 'FAILED')),
    -- Populated only when status = FAILED; describes the failure reason.
    failure_reason   TEXT,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- A transfer must move money between two distinct accounts.
    CONSTRAINT transfers_different_accounts_chk
        CHECK (from_account_id <> to_account_id)
);

COMMENT ON TABLE  transfers                       IS 'Authorised debit-credit transfer requests.';
COMMENT ON COLUMN transfers.idempotency_key        IS 'Caller-supplied deduplication key; unique per request.';
COMMENT ON COLUMN transfers.request_hash           IS 'Hash of full request payload for tamper detection.';
COMMENT ON COLUMN transfers.amount_minor           IS 'Transfer amount in minor currency units; strictly > 0.';
COMMENT ON COLUMN transfers.failure_reason         IS 'Non-null only when status = FAILED.';

-- ---------------------------------------------------------------------------
-- ledger_entries
-- Immutable double-entry bookkeeping record.
-- Each transfer produces exactly two entries: one DEBIT, one CREDIT.
-- INSERT-ONLY: a database trigger prevents any UPDATE or DELETE.
-- ---------------------------------------------------------------------------
CREATE TABLE ledger_entries (
    id           UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    transfer_id  UUID        NOT NULL
                 REFERENCES transfers(id),
    account_id   UUID        NOT NULL
                 REFERENCES accounts(id),
    -- DEBIT decreases the account balance; CREDIT increases it.
    direction    VARCHAR(6)  NOT NULL
                 CONSTRAINT ledger_entries_direction_chk
                 CHECK (direction IN ('DEBIT', 'CREDIT')),
    -- Amount in minor units; always positive; sign is encoded in direction.
    amount_minor BIGINT      NOT NULL
                 CONSTRAINT ledger_entries_amount_positive_chk
                 CHECK (amount_minor > 0),
    currency     CHAR(3)     NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
    -- No updated_at: rows are immutable; the trigger below enforces this.
);

COMMENT ON TABLE  ledger_entries              IS 'Immutable double-entry ledger; INSERT-only enforced by trigger.';
COMMENT ON COLUMN ledger_entries.direction     IS 'DEBIT (balance decreases) or CREDIT (balance increases).';
COMMENT ON COLUMN ledger_entries.amount_minor  IS 'Absolute amount in minor currency units; always > 0.';

-- Indexes to support the common query patterns:
-- "all entries for an account" and "entries that belong to a transfer".
CREATE INDEX ledger_entries_account_id_idx  ON ledger_entries (account_id);
CREATE INDEX ledger_entries_transfer_id_idx ON ledger_entries (transfer_id);

-- ---------------------------------------------------------------------------
-- Trigger: enforce INSERT-only on ledger_entries
-- Any attempt to UPDATE or DELETE a ledger row raises an exception.
-- This provides a database-level guarantee independent of application code.
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION ledger_entries_immutable()
RETURNS TRIGGER
LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION
        'ledger_entries is INSERT-only: % on row id=% is forbidden',
        TG_OP,
        COALESCE(OLD.id::TEXT, 'unknown');
END;
$$;

-- Fires BEFORE UPDATE or DELETE (not AFTER) so the row is never touched.
CREATE TRIGGER ledger_entries_no_update_delete
    BEFORE UPDATE OR DELETE ON ledger_entries
    FOR EACH ROW EXECUTE FUNCTION ledger_entries_immutable();
