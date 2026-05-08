# Mock Data Seeding Runbook

## Purpose
Populate deterministic local test data for manual verification of ledger, orders, withdrawals, settlement, and reconciliation.

## Safety
- Seeding is blocked unless:
  - `local` profile is active, or
  - `ledger.seed.enabled=true` is explicitly set.
- `reset=true` is destructive for business tables.

## Endpoints
- `POST /api/v1/admin/seed/reset`
- `POST /api/v1/admin/seed/run?dataset=medium&reset=true`
- `POST /api/v1/admin/seed/scenario?name=happy-path&reset=false`
- `GET /api/v1/admin/seed/catalog`

## Example Commands
```bash
curl -X POST "http://localhost:8080/api/v1/admin/seed/run?dataset=medium&reset=true"
curl -X POST "http://localhost:8080/api/v1/admin/seed/scenario?name=reconciliation-mismatch&reset=true"
curl -X POST "http://localhost:8080/api/v1/admin/seed/reset"
curl "http://localhost:8080/api/v1/admin/seed/catalog"
```

## Known IDs (deterministic)
Fetch from:
- `GET /api/v1/admin/seed/catalog`

Useful keys:
- `firstCustomerId`
- `firstSettledCashAccountId`
- `firstOrderReferenceId`
- `firstWithdrawalId`

## Post-Seed Verification
1. Ledger balance
```bash
curl "http://localhost:8080/api/v1/ledger/accounts/<firstSettledCashAccountId>/balance"
```
2. Buying power
```bash
curl "http://localhost:8080/api/v1/orders/buying-power/<firstCustomerId>?currency=AED"
```
3. Withdrawal status
```bash
curl "http://localhost:8080/api/v1/cash/withdrawals/<firstWithdrawalId>"
```
4. Settlement and reconciliation manual triggers
```bash
curl -X POST "http://localhost:8080/api/v1/admin/jobs/settlement/run?settlementDate=$(date +%F)&batchId=seed-manual-$(date +%F)"
curl -X POST "http://localhost:8080/api/v1/admin/jobs/reconciliation/run"
```

## Scenarios
- `happy-path`
- `reconciliation-mismatch` (intentionally corrupts one snapshot balance)
- `settlement-pending` (keeps non-terminal order states for settlement skip validation)
