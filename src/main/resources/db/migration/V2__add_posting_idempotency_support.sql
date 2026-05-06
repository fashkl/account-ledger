DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'journal_entries_idempotency_key_key'
    ) THEN
        ALTER TABLE journal_entries DROP CONSTRAINT journal_entries_idempotency_key_key;
    END IF;
END $$;

CREATE TABLE IF NOT EXISTS ledger_postings (
    idempotency_key TEXT PRIMARY KEY,
    entry_group_id UUID NOT NULL,
    event_type TEXT NOT NULL,
    reference_id UUID,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_ledger_postings_reference
    ON ledger_postings (reference_id);
