-- V4__ledger_statement_index.sql
-- Optimizes paginated ledger statement retrieval for accounts ordered newest-first.

CREATE INDEX IF NOT EXISTS ledger_entries_statement_idx
    ON ledger_entries (account_id, created_at DESC, id DESC);
