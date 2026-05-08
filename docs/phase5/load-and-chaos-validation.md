# Phase 5 Load and Chaos Validation

## SLO Targets
- Posting p99 < 50ms
- Balance read p99 < 10ms
- Reconciliation query p99 < 5s

## Mandatory Chaos Scenarios
1. DB transient outage/failover
2. Kafka broker restart
3. Hikari pool saturation
4. Reconciliation overlap contention

## Repeatable Load Profiles
- `docs/phase5/k6/posting-load.js` (posting p99 target `<50ms`, requires seeded valid posting payloads)
- `docs/phase5/k6/balance-read-load.js` (buying power reads p99 target `<10ms`)
- `docs/phase5/k6/reconciliation-load.js` (reconciliation trigger p99 target `<5s`)

Run examples:
- `k6 run docs/phase5/k6/posting-load.js -e BASE_URL=http://localhost:8080`
- `k6 run docs/phase5/k6/balance-read-load.js -e BASE_URL=http://localhost:8080 -e CUSTOMER_ID=<uuid>`
- `k6 run docs/phase5/k6/reconciliation-load.js -e BASE_URL=http://localhost:8080`

## Kafka / Alert Baselines
- Alert when `kafka_consumer_lag_seconds > 60` for `5m`.
- Alert when `kafka_events_failed_total` delta is non-zero over `5m`.
- Alert when `kafka_dlq_published_total` delta is non-zero over `5m`.

## Pass Criteria
- No duplicate postings
- No silent data loss
- Recovery within bounded window
- Alerts and structured logs emitted
