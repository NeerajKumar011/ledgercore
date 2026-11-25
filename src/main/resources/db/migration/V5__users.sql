-- V5__users.sql
-- Users table for authentication, role-based authorization, and account ownership mapping

CREATE TABLE users (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email VARCHAR(254) NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    role VARCHAR(10) NOT NULL CHECK (role IN ('CUSTOMER', 'ADMIN')),
    enabled BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Case-insensitive unique index on normalized email
CREATE UNIQUE INDEX users_email_lower_uidx ON users (lower(email));

-- Add owner_user_id to accounts (nullable for existing accounts and SYSTEM funding accounts)
ALTER TABLE accounts ADD COLUMN owner_user_id UUID REFERENCES users(id);

-- Performance index for account queries filtered by owner
CREATE INDEX accounts_owner_user_id_idx ON accounts (owner_user_id);
