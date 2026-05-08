# Reconciliation Mismatch Runbook

1. Inspect latest `reconciliation_runs` and `reconciliation_issues` rows.
2. Confirm mismatch scope (single account vs broad drift).
3. If isolated, freeze affected account operations and run snapshot rebuild dry run.
4. If broad, halt settlement scheduler, escalate to DBA/on-call.
5. After correction, rerun reconciliation and verify `mismatchCount=0`.
