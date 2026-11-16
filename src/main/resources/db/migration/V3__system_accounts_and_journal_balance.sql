-- =============================================================================
-- V3__system_accounts_and_journal_balance.sql
-- LedgerCore system accounts and double-entry journal balance enforcement
-- PostgreSQL 18 syntax; Flyway runs this exactly once, after V2.
-- =============================================================================

-- ---------------------------------------------------------------------------
-- 1a. Account kind column
--     Distinguishes regular customer accounts from internal system funding
--     accounts. Defaults to CUSTOMER for all existing rows.
-- ---------------------------------------------------------------------------
ALTER TABLE accounts
    ADD COLUMN kind VARCHAR(10) NOT NULL DEFAULT 'CUSTOMER'
    CONSTRAINT accounts_kind_chk CHECK (kind IN ('CUSTOMER', 'SYSTEM'));

COMMENT ON COLUMN accounts.kind IS 'CUSTOMER | SYSTEM account type.';

-- ---------------------------------------------------------------------------
-- 1b. Balance non-negative check updated for system accounts
--     CUSTOMER accounts must maintain balance_minor >= 0.
--     SYSTEM accounts (funding sources) are permitted to go negative.
--     Re-created with the same constraint name for backwards compatibility.
-- ---------------------------------------------------------------------------
ALTER TABLE accounts
    DROP CONSTRAINT accounts_balance_non_negative_chk;

ALTER TABLE accounts
    ADD CONSTRAINT accounts_balance_non_negative_chk
    CHECK (kind = 'SYSTEM' OR balance_minor >= 0);

-- ---------------------------------------------------------------------------
-- 1c. Partial unique index for SYSTEM funding accounts
--     Enforces at most one SYSTEM account per currency.
-- ---------------------------------------------------------------------------
CREATE UNIQUE INDEX accounts_system_currency_uidx
    ON accounts (currency)
    WHERE (kind = 'SYSTEM');

-- ---------------------------------------------------------------------------
-- 1d. Deferred constraint trigger enforcing balanced double-entry journal
--     Fires AFTER INSERT on ledger_entries, DEFERRABLE INITIALLY DEFERRED.
--     At commit time, verifies that for NEW.transfer_id there are exactly two
--     entries: exactly 1 DEBIT and 1 CREDIT, with matching amounts and currencies.
--     The database refuses to commit any unbalanced journal entry.
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION ledger_entries_check_journal_balance()
RETURNS TRIGGER
LANGUAGE plpgsql AS $$
DECLARE
    v_count           INTEGER;
    v_debit_count     INTEGER;
    v_credit_count    INTEGER;
    v_debit_amount    BIGINT;
    v_credit_amount   BIGINT;
    v_debit_currency  CHAR(3);
    v_credit_currency CHAR(3);
BEGIN
    SELECT
        COUNT(*),
        COUNT(*) FILTER (WHERE direction = 'DEBIT'),
        COUNT(*) FILTER (WHERE direction = 'CREDIT'),
        COALESCE(SUM(amount_minor) FILTER (WHERE direction = 'DEBIT'), 0),
        COALESCE(SUM(amount_minor) FILTER (WHERE direction = 'CREDIT'), 0),
        MAX(currency) FILTER (WHERE direction = 'DEBIT'),
        MAX(currency) FILTER (WHERE direction = 'CREDIT')
    INTO
        v_count,
        v_debit_count,
        v_credit_count,
        v_debit_amount,
        v_credit_amount,
        v_debit_currency,
        v_credit_currency
    FROM ledger_entries
    WHERE transfer_id = NEW.transfer_id;

    IF v_count <> 2
       OR v_debit_count <> 1
       OR v_credit_count <> 1
       OR v_debit_amount <> v_credit_amount
       OR v_debit_currency <> v_credit_currency THEN
        RAISE EXCEPTION
            'Unbalanced journal for transfer %: expected exactly 1 DEBIT and 1 CREDIT with matching amount and currency, found % entries (DEBIT count=%, amount=%, curr=%; CREDIT count=%, amount=%, curr=%)',
            NEW.transfer_id, v_count, v_debit_count, v_debit_amount, v_debit_currency, v_credit_count, v_credit_amount, v_credit_currency;
    END IF;

    RETURN NEW;
END;
$$;

CREATE CONSTRAINT TRIGGER ledger_entries_journal_balanced_chk
    AFTER INSERT ON ledger_entries
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION ledger_entries_check_journal_balance();
