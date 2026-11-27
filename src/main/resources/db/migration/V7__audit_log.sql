-- =============================================================================
-- V7__audit_log.sql
-- Phase 4, Step C: Tamper-evident hash-chained audit log and refresh token constraints
-- PostgreSQL 18 syntax; Flyway runs this exactly once, after V6.
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 1. Refresh Token & User Constraints & Indexes
-- -----------------------------------------------------------------------------
CREATE INDEX IF NOT EXISTS idx_refresh_tokens_expires_at ON refresh_tokens (expires_at);

ALTER TABLE users
    ADD CONSTRAINT users_failed_login_count_chk CHECK (failed_login_count >= 0);

ALTER TABLE refresh_tokens
    ADD CONSTRAINT refresh_tokens_expires_after_created_chk CHECK (expires_at > created_at);

-- Ensure token cleanup can delete parent tokens without foreign key restriction
ALTER TABLE refresh_tokens
    DROP CONSTRAINT IF EXISTS refresh_tokens_replaced_by_fkey;

ALTER TABLE refresh_tokens
    ADD CONSTRAINT refresh_tokens_replaced_by_fkey
    FOREIGN KEY (replaced_by) REFERENCES refresh_tokens(id) ON DELETE SET NULL;

-- -----------------------------------------------------------------------------
-- 2. Audit Events Table
-- -----------------------------------------------------------------------------
CREATE TABLE audit_events (
    seq             BIGINT PRIMARY KEY,
    id              UUID NOT NULL UNIQUE,
    occurred_at     TIMESTAMPTZ NOT NULL,
    actor_user_id   UUID NULL,
    actor_role      VARCHAR(10) NULL,
    action          VARCHAR(40) NOT NULL,
    outcome         VARCHAR(10) NOT NULL
                    CONSTRAINT audit_events_outcome_chk CHECK (outcome IN ('SUCCESS', 'FAILURE', 'DENIED')),
    target_type     VARCHAR(20) NULL,
    target_id       VARCHAR(64) NULL,
    request_id      VARCHAR(64) NULL,
    client_ip       VARCHAR(45) NULL,
    details         JSONB NULL,
    prev_hash       CHAR(64) NOT NULL,
    row_hash        CHAR(64) NOT NULL
);

CREATE INDEX idx_audit_events_occurred_at ON audit_events (occurred_at DESC);
CREATE INDEX idx_audit_events_action ON audit_events (action);
CREATE INDEX idx_audit_events_actor ON audit_events (actor_user_id);

-- -----------------------------------------------------------------------------
-- 3. Immutability Triggers (Insert-only: UPDATE, DELETE, and TRUNCATE forbidden)
-- -----------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION audit_events_no_update_or_delete()
RETURNS TRIGGER
LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'audit_events is INSERT-only: UPDATE and DELETE are forbidden';
END;
$$;

CREATE TRIGGER audit_events_prevent_update_or_delete
BEFORE UPDATE OR DELETE ON audit_events
FOR EACH ROW
EXECUTE FUNCTION audit_events_no_update_or_delete();

CREATE OR REPLACE FUNCTION audit_events_no_truncate()
RETURNS TRIGGER
LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'audit_events is INSERT-only: TRUNCATE is forbidden';
END;
$$;

CREATE TRIGGER audit_events_prevent_truncate
BEFORE TRUNCATE ON audit_events
EXECUTE FUNCTION audit_events_no_truncate();
