# Circuit Breaker Open Runbook

## Purpose
Guide operators when the `ledgerProcessing` circuit breaker opens and Kafka consumption is paused.

## Signals
- API responses return `503 Service Unavailable` with `Retry-After: 30`.
- Logs show:
  - `Circuit breaker opened, kafka consumer paused`
  - `Kafka listener paused listenerId=ledger-events-consumer reason=circuit breaker OPEN`
- Metrics/actuator:
  - `/actuator/circuitbreakers`
  - `kafka_consumer_paused`
  - `kafka_consumer_lag_records`
  - `kafka_events_paused_total{reason="circuit_open"}`

## Immediate Triage
1. Confirm breaker state:
   - `GET /actuator/circuitbreakers/ledgerProcessing`
2. Check DB health and saturation:
   - connection pool exhaustion
   - lock waits
   - failover in progress
3. Check Kafka lag and DLQ growth:
   - lag should rise while consumer is paused
   - DLQ should not increase due to open-circuit events

## Decision Tree
1. If DB/system is still degraded:
   - keep breaker open behavior as-is
   - do not force resume consumer
   - stabilize DB first (capacity, failover completion, lock contention)
2. If DB/system recovered:
   - wait for breaker to move `OPEN -> HALF_OPEN -> CLOSED` automatically
   - verify consumer resumes (`kafka_consumer_paused=0`)
3. If breaker flaps repeatedly:
   - treat as systemic incident
   - escalate to platform + DB owners
   - throttle upstream producers if required

## Recovery Verification
1. Breaker is `CLOSED`.
2. Kafka consumer resumed:
   - log line `Kafka listener resumed ...`
   - lag trend decreases.
3. Posting success rates normal:
   - `kafka_events_failed_total` stable
   - API 5xx rate back to baseline.

## DLQ Handling After Recovery
1. Inspect DLQ records and reason headers:
   - `x-dlq-reason`
   - `x-dlq-stack-hash`
2. Replay only records caused by transient failures.
3. Keep permanently invalid payloads quarantined with incident reference.

## Escalation Criteria
- Breaker stays open longer than 10 minutes.
- Breaker reopens more than 3 times in 15 minutes.
- Consumer lag exceeds agreed SLO and not decreasing after resume.
- DLQ growth continues after system recovery.

## Post-Incident Checklist
1. Capture timeline (open time, recover time, lag peak, recovery duration).
2. Record root cause and remediation.
3. Add regression test/alert tuning if needed.
