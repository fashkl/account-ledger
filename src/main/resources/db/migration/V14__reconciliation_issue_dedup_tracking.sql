ALTER TABLE reconciliation_issues
    ADD COLUMN IF NOT EXISTS issue_fingerprint TEXT;

ALTER TABLE reconciliation_issues
    ADD COLUMN IF NOT EXISTS first_seen_at TIMESTAMPTZ NOT NULL DEFAULT now();

ALTER TABLE reconciliation_issues
    ADD COLUMN IF NOT EXISTS last_seen_at TIMESTAMPTZ NOT NULL DEFAULT now();

ALTER TABLE reconciliation_issues
    ADD COLUMN IF NOT EXISTS occurrence_count INTEGER NOT NULL DEFAULT 1;

CREATE UNIQUE INDEX IF NOT EXISTS ux_reconciliation_issues_issue_type_fingerprint
    ON reconciliation_issues (issue_type, issue_fingerprint);

