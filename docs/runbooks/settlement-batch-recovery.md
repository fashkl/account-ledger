# Settlement Batch Recovery Runbook

1. Inspect `settlement_batches` for RUNNING/FAILED rows and checkpoint.
2. Inspect linked `reconciliation_runs` rows and status.
3. Fix dependency failure (DB, lock contention, schema drift).
4. Re-run same batch id; verify checkpoint resume without duplicate posting.
5. Validate completion via `status=DONE` and reconciliation summary.
