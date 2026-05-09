# Advanced k6 Load Suite

## Purpose
Exercise realistic mixed traffic, shock behavior, and account-level contention before production rollout.

## Prerequisites

1. App is running locally:
```bash
./gradlew bootRun
```

2. Seed deterministic data:
```bash
curl -X POST "http://localhost:8080/api/v1/admin/seed/run?dataset=medium&reset=true"
```

3. Fetch seeded IDs:
```bash
curl "http://localhost:8080/api/v1/admin/seed/catalog"
```

Use these values from `knownIds`:
- `firstCustomerId`
- `firstSettledCashAccountId`
- `firstOrderReferenceId` (optional for manual checks)
- `firstWithdrawalId` (optional for manual checks)

## Common Environment

```bash
export BASE_URL=http://localhost:8080
export CUSTOMER_ID=<firstCustomerId>
export SETTLED_CASH_ACCOUNT_ID=<firstSettledCashAccountId>
export RESERVED_CASH_ACCOUNT_ID=<reservedCashAccountId>
export UNSETTLED_CASH_BUYS_ACCOUNT_ID=<unsettledCashBuysAccountId>
export SETTLEMENT_PENDING_ACCOUNT_ID=<settlementPendingAccountId>
export BROKERAGE_OMNIBUS_ACCOUNT_ID=<brokerageOmnibusAccountId>
export CURRENCY=AED
```

`reserved/unsettled/pending/omnibus` IDs can be copied from DB or previous scenario outputs if you already track them.  
If omitted, scripts use fallback UUID placeholders and may produce high 4xx rates.

## Scenarios

### 1) Mixed traffic

File: `docs/k6/mixed-traffic-load.js`

Traffic model:
- buying power reads: 60 -> 120 -> 200 RPS
- order events: 20 -> 50 -> 100 RPS
- cash events: 20 -> 40 -> 80 RPS

Run:
```bash
k6 run docs/k6/mixed-traffic-load.js \
  -e BASE_URL=$BASE_URL \
  -e CUSTOMER_ID=$CUSTOMER_ID \
  -e SETTLED_CASH_ACCOUNT_ID=$SETTLED_CASH_ACCOUNT_ID \
  -e RESERVED_CASH_ACCOUNT_ID=$RESERVED_CASH_ACCOUNT_ID \
  -e UNSETTLED_CASH_BUYS_ACCOUNT_ID=$UNSETTLED_CASH_BUYS_ACCOUNT_ID \
  -e SETTLEMENT_PENDING_ACCOUNT_ID=$SETTLEMENT_PENDING_ACCOUNT_ID \
  -e BROKERAGE_OMNIBUS_ACCOUNT_ID=$BROKERAGE_OMNIBUS_ACCOUNT_ID \
  -e CURRENCY=$CURRENCY
```

Pass criteria:
- `http_req_failed < 2%`
- `http_req_duration p(99) < 120ms`
- `checks > 99%`

### 2) Spike and soak

File: `docs/k6/spike-and-soak-load.js`

Traffic model:
- ramp to 100 RPS
- spike to 1200 RPS
- sustain spike 3m
- soak at 500 RPS for 10m
- cool down

Run:
```bash
k6 run docs/k6/spike-and-soak-load.js \
  -e BASE_URL=$BASE_URL \
  -e CUSTOMER_ID=$CUSTOMER_ID \
  -e CURRENCY=$CURRENCY
```

Pass criteria:
- `http_req_failed < 2%`
- `http_req_duration p(99) < 80ms`
- `http_req_duration p(95) < 35ms`
- no restart/crash/recovery-loop in app logs

### 3) Hotspot contention

File: `docs/k6/hotspot-contention-load.js`

Traffic model:
- repeatedly hits the same `withdrawalId` and customer cash accounts
- forces lock/contention paths under high parallel arrival

Run:
```bash
k6 run docs/k6/hotspot-contention-load.js \
  -e BASE_URL=$BASE_URL \
  -e CUSTOMER_ID=$CUSTOMER_ID \
  -e SETTLED_CASH_ACCOUNT_ID=$SETTLED_CASH_ACCOUNT_ID \
  -e SETTLEMENT_PENDING_ACCOUNT_ID=$SETTLEMENT_PENDING_ACCOUNT_ID \
  -e BROKERAGE_OMNIBUS_ACCOUNT_ID=$BROKERAGE_OMNIBUS_ACCOUNT_ID \
  -e HOTSPOT_WITHDRAWAL_ID=99999999-9999-9999-9999-999999999999 \
  -e CURRENCY=$CURRENCY
```

Pass criteria:
- `http_req_failed < 3%`
- `http_req_duration p(99) < 250ms`
- `http_req_duration p(95) < 120ms`
- no 5xx bursts; only expected 2xx/4xx statuses

## Failure triage checklist

1. Check `http_req_failed` first:
- if high with mostly 4xx, verify seed IDs and env vars.

2. If p99 spikes:
- check DB pool saturation, lock waits, and Kafka background load.

3. If 5xx appear:
- capture app logs with correlation IDs and inspect reconciliation/settlement overlap.

4. Record benchmark output:
- keep p50/p95/p99 + failure rate per run for trend comparison.
