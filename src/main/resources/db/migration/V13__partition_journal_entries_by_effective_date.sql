DO $$
DECLARE
    is_partitioned BOOLEAN;
BEGIN
    SELECT EXISTS(
        SELECT 1
        FROM pg_partitioned_table pt
        JOIN pg_class c ON c.oid = pt.partrelid
        WHERE c.relname = 'journal_entries'
    ) INTO is_partitioned;

    IF is_partitioned THEN
        RETURN;
    END IF;

    ALTER TABLE journal_entries RENAME TO journal_entries_old;

    CREATE TABLE journal_entries (
        id UUID NOT NULL DEFAULT gen_random_uuid(),
        entry_group_id UUID NOT NULL,
        account_id UUID NOT NULL REFERENCES accounts (id),
        direction TEXT NOT NULL CHECK (direction IN ('DEBIT', 'CREDIT')),
        amount NUMERIC(20, 8) NOT NULL CHECK (amount > 0),
        currency TEXT NOT NULL,
        event_type TEXT NOT NULL,
        reference_id UUID,
        effective_date DATE NOT NULL,
        created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
        idempotency_key TEXT,
        PRIMARY KEY (id, effective_date)
    ) PARTITION BY RANGE (effective_date);

    CREATE TABLE journal_entries_default PARTITION OF journal_entries DEFAULT;

    CREATE TABLE journal_entries_2025_01 PARTITION OF journal_entries
        FOR VALUES FROM ('2025-01-01') TO ('2025-02-01');
    CREATE TABLE journal_entries_2025_02 PARTITION OF journal_entries
        FOR VALUES FROM ('2025-02-01') TO ('2025-03-01');
    CREATE TABLE journal_entries_2025_03 PARTITION OF journal_entries
        FOR VALUES FROM ('2025-03-01') TO ('2025-04-01');
    CREATE TABLE journal_entries_2025_04 PARTITION OF journal_entries
        FOR VALUES FROM ('2025-04-01') TO ('2025-05-01');
    CREATE TABLE journal_entries_2025_05 PARTITION OF journal_entries
        FOR VALUES FROM ('2025-05-01') TO ('2025-06-01');
    CREATE TABLE journal_entries_2025_06 PARTITION OF journal_entries
        FOR VALUES FROM ('2025-06-01') TO ('2025-07-01');
    CREATE TABLE journal_entries_2025_07 PARTITION OF journal_entries
        FOR VALUES FROM ('2025-07-01') TO ('2025-08-01');
    CREATE TABLE journal_entries_2025_08 PARTITION OF journal_entries
        FOR VALUES FROM ('2025-08-01') TO ('2025-09-01');
    CREATE TABLE journal_entries_2025_09 PARTITION OF journal_entries
        FOR VALUES FROM ('2025-09-01') TO ('2025-10-01');
    CREATE TABLE journal_entries_2025_10 PARTITION OF journal_entries
        FOR VALUES FROM ('2025-10-01') TO ('2025-11-01');
    CREATE TABLE journal_entries_2025_11 PARTITION OF journal_entries
        FOR VALUES FROM ('2025-11-01') TO ('2025-12-01');
    CREATE TABLE journal_entries_2025_12 PARTITION OF journal_entries
        FOR VALUES FROM ('2025-12-01') TO ('2026-01-01');
    CREATE TABLE journal_entries_2026_01 PARTITION OF journal_entries
        FOR VALUES FROM ('2026-01-01') TO ('2026-02-01');
    CREATE TABLE journal_entries_2026_02 PARTITION OF journal_entries
        FOR VALUES FROM ('2026-02-01') TO ('2026-03-01');
    CREATE TABLE journal_entries_2026_03 PARTITION OF journal_entries
        FOR VALUES FROM ('2026-03-01') TO ('2026-04-01');
    CREATE TABLE journal_entries_2026_04 PARTITION OF journal_entries
        FOR VALUES FROM ('2026-04-01') TO ('2026-05-01');
    CREATE TABLE journal_entries_2026_05 PARTITION OF journal_entries
        FOR VALUES FROM ('2026-05-01') TO ('2026-06-01');
    CREATE TABLE journal_entries_2026_06 PARTITION OF journal_entries
        FOR VALUES FROM ('2026-06-01') TO ('2026-07-01');
    CREATE TABLE journal_entries_2026_07 PARTITION OF journal_entries
        FOR VALUES FROM ('2026-07-01') TO ('2026-08-01');
    CREATE TABLE journal_entries_2026_08 PARTITION OF journal_entries
        FOR VALUES FROM ('2026-08-01') TO ('2026-09-01');
    CREATE TABLE journal_entries_2026_09 PARTITION OF journal_entries
        FOR VALUES FROM ('2026-09-01') TO ('2026-10-01');
    CREATE TABLE journal_entries_2026_10 PARTITION OF journal_entries
        FOR VALUES FROM ('2026-10-01') TO ('2026-11-01');
    CREATE TABLE journal_entries_2026_11 PARTITION OF journal_entries
        FOR VALUES FROM ('2026-11-01') TO ('2026-12-01');
    CREATE TABLE journal_entries_2026_12 PARTITION OF journal_entries
        FOR VALUES FROM ('2026-12-01') TO ('2027-01-01');

    INSERT INTO journal_entries (id, entry_group_id, account_id, direction, amount, currency, event_type, reference_id, effective_date, created_at, idempotency_key)
    SELECT id, entry_group_id, account_id, direction, amount, currency, event_type, reference_id, effective_date, created_at, idempotency_key
    FROM journal_entries_old;

    DROP TABLE journal_entries_old;

    CREATE INDEX idx_journal_entries_account_created
        ON journal_entries (account_id, created_at);
    CREATE INDEX idx_journal_entries_reference
        ON journal_entries (reference_id);
    CREATE INDEX idx_journal_entries_effective_date
        ON journal_entries (effective_date);
    CREATE INDEX idx_journal_entries_entry_group
        ON journal_entries (entry_group_id);

    CREATE TRIGGER trg_prevent_journal_entries_mutation
    BEFORE UPDATE OR DELETE ON journal_entries
    FOR EACH ROW
    EXECUTE FUNCTION prevent_journal_entries_mutation();
END $$;
