CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE TABLE IF NOT EXISTS accounts (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    customer_id UUID NOT NULL,
    type TEXT NOT NULL,
    currency TEXT NOT NULL DEFAULT 'AED',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (customer_id, type, currency)
);

CREATE TABLE IF NOT EXISTS journal_entries (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    entry_group_id UUID NOT NULL,
    account_id UUID NOT NULL REFERENCES accounts (id),
    direction TEXT NOT NULL CHECK (direction IN ('DEBIT', 'CREDIT')),
    amount NUMERIC(20, 8) NOT NULL CHECK (amount > 0),
    currency TEXT NOT NULL,
    event_type TEXT NOT NULL,
    reference_id UUID,
    effective_date DATE NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    idempotency_key TEXT UNIQUE
);

CREATE INDEX IF NOT EXISTS idx_journal_entries_account_created
    ON journal_entries (account_id, created_at);

CREATE INDEX IF NOT EXISTS idx_journal_entries_reference
    ON journal_entries (reference_id);

CREATE INDEX IF NOT EXISTS idx_journal_entries_effective_date
    ON journal_entries (effective_date);

CREATE TABLE IF NOT EXISTS account_balances (
    account_id UUID PRIMARY KEY REFERENCES accounts (id),
    balance NUMERIC(20, 8) NOT NULL DEFAULT 0,
    version BIGINT NOT NULL DEFAULT 0,
    last_entry_id UUID,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
