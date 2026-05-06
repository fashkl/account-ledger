ALTER TABLE accounts
    ADD COLUMN IF NOT EXISTS status TEXT NOT NULL DEFAULT 'ACTIVE';

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'accounts_status_check'
    ) THEN
        ALTER TABLE accounts
            ADD CONSTRAINT accounts_status_check CHECK (status IN ('ACTIVE', 'CLOSED'));
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_journal_entries_entry_group
    ON journal_entries (entry_group_id);

CREATE TABLE IF NOT EXISTS order_states (
    reference_id UUID PRIMARY KEY,
    state TEXT NOT NULL CHECK (state IN ('HOLD', 'PARTIALLY_FILLED', 'FILLED', 'CANCELLED', 'REJECTED')),
    held_amount NUMERIC(20, 8) NOT NULL,
    filled_amount NUMERIC(20, 8) NOT NULL DEFAULT 0,
    currency TEXT NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE OR REPLACE FUNCTION prevent_journal_entries_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'journal_entries is immutable; use reversal entries';
END;
$$;

DROP TRIGGER IF EXISTS trg_prevent_journal_entries_mutation ON journal_entries;

CREATE TRIGGER trg_prevent_journal_entries_mutation
BEFORE UPDATE OR DELETE ON journal_entries
FOR EACH ROW
EXECUTE FUNCTION prevent_journal_entries_mutation();
