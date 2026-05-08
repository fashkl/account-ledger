# Journal Invariant Incident Runbook

1. Page on-call immediately for any `JOURNAL_INVARIANT` issue.
2. Query all rows by `entry_group_id` and verify debit/credit totals.
3. Stop downstream settlement for impacted references.
4. Apply reversal-based correction only (never mutate journal rows).
5. Re-run reconciliation and capture incident timeline.
