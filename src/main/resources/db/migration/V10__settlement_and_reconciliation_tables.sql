CREATE TABLE IF NOT EXISTS settlement_batches (
    batch_id TEXT PRIMARY KEY,
    settlement_date DATE NOT NULL,
    status TEXT NOT NULL CHECK (status IN ('RUNNING', 'DONE', 'FAILED')),
    entries_processed INTEGER NOT NULL DEFAULT 0,
    started_at TIMESTAMPTZ NOT NULL,
    finished_at TIMESTAMPTZ,
    error_message TEXT
);

CREATE INDEX IF NOT EXISTS idx_settlement_batches_date_status
    ON settlement_batches (settlement_date, status);

CREATE TABLE IF NOT EXISTS reconciliation_runs (
    run_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    run_type TEXT NOT NULL CHECK (run_type IN ('PERIODIC', 'BANK_DAILY')),
    status TEXT NOT NULL CHECK (status IN ('RUNNING', 'DONE', 'FAILED')),
    started_at TIMESTAMPTZ NOT NULL,
    finished_at TIMESTAMPTZ,
    mismatch_count INTEGER NOT NULL DEFAULT 0,
    invariant_violation_count INTEGER NOT NULL DEFAULT 0,
    error_message TEXT
);

CREATE INDEX IF NOT EXISTS idx_reconciliation_runs_started
    ON reconciliation_runs (started_at DESC);

CREATE TABLE IF NOT EXISTS reconciliation_issues (
    issue_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    run_id UUID REFERENCES reconciliation_runs(run_id),
    issue_type TEXT NOT NULL CHECK (issue_type IN ('SNAPSHOT_MISMATCH', 'JOURNAL_INVARIANT', 'SETTLEMENT_SKIPPED', 'BANK_STATEMENT_MISMATCH')),
    severity TEXT NOT NULL CHECK (severity IN ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL')),
    account_id UUID,
    reference_id UUID,
    details JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    resolved BOOLEAN NOT NULL DEFAULT false
);

CREATE INDEX IF NOT EXISTS idx_reconciliation_issues_created
    ON reconciliation_issues (created_at DESC);

CREATE INDEX IF NOT EXISTS idx_reconciliation_issues_unresolved
    ON reconciliation_issues (resolved)
    WHERE resolved = false;

