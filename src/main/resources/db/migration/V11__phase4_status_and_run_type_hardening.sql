DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'settlement_batches_status_check'
    ) THEN
        ALTER TABLE settlement_batches DROP CONSTRAINT settlement_batches_status_check;
    END IF;
END $$;

ALTER TABLE settlement_batches
    ADD CONSTRAINT settlement_batches_status_check
    CHECK (status IN ('PENDING', 'RUNNING', 'DONE', 'FAILED'));

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'reconciliation_runs_run_type_check'
    ) THEN
        ALTER TABLE reconciliation_runs DROP CONSTRAINT reconciliation_runs_run_type_check;
    END IF;
END $$;

ALTER TABLE reconciliation_runs
    ADD CONSTRAINT reconciliation_runs_run_type_check
    CHECK (run_type IN ('PERIODIC', 'BANK_DAILY', 'SETTLEMENT'));

