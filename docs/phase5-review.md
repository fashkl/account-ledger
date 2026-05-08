# Phase 5 Review — Hardening and Scale Validation

**Reviewed against:** `IMPLEMENTATION_PLAN.md` Phase 5 (Week 6)
**Review date:** 2026-05-08
**Status:** 8 issues open (2 Critical, 3 High, 3 Medium) + 6 unresolved issues carried from Phase 4

---

## Summary

Phase 5 covers Resilience4j circuit breaker integration, Kafka consumer hardening, read replica wiring, load/chaos tests, and operational runbooks. The structural pieces are mostly in place (circuit breaker beans, retry config, DLQ routing, read replica DataSource, k6 scripts, 4 runbooks), but several implementations diverge from the plan in ways that break the operational guarantees they are meant to provide.

---

## What Is Correctly Implemented

| Item | Status |
|---|---|
| Resilience4j circuit breaker on ledger processing path | ✅ `@CircuitBreaker(name = "ledgerProcessing")` on `LedgerEventProcessor.process()` |
| Resilience4j retry on processing path | ✅ `@Retry(name = "ledgerProcessingRetry")` with exponential backoff |
| Circuit breaker → Kafka consumer pause/resume | ✅ `CircuitBreakerLifecycleListener` subscribes to state transitions; OPEN → pause, HALF_OPEN/CLOSED → resume |
| DLQ routing via `DeadLetterPublishingRecoverer` | ✅ Exponential backoff 500ms × 2.0, max 3 attempts; `IllegalArgumentException` non-retryable |
| DLQ headers (`x-dlq-reason`, `x-dlq-message`, `x-dlq-stack-hash`, `x-event-id`) | ✅ |
| Read replica DataSource bean | ✅ `ReadReplicaDataSourceConfig`; `getAccountBalance` and `buyingPower` use `readReplicaJdbcTemplate` |
| Read replica disabled by default (falls back to primary) | ✅ `ledger.datasource.read-replica.enabled: false` |
| k6 load test scripts (posting, balance reads, reconciliation) | ✅ 3 scripts exist in `docs/phase5/k6/` |
| Chaos scenarios documented | ✅ `docs/phase5/load-and-chaos-validation.md` |
| 4 of 5 required runbooks | ✅ reconciliation-mismatch, settlement-batch-recovery, consumer-lag-escalation, journal-invariant-incident |
| Admin endpoints for kafka pause/resume and manual job trigger | ✅ `JobsAdminController` at `/api/v1/admin/jobs/...` |
| Partition key validation on Kafka consumer | ✅ `validatePartitionKey` enforces `record.key() == event.customerId()` |
| MDC enrichment and per-event metrics | ✅ `DomainMdc`, `meterRegistry.counter(...)` on success and failure paths |

---

## Issues

### Critical

---

#### C-1 — Circuit breaker applied to wrong architectural layer

**Plan says:** Apply `@CircuitBreaker` to `LedgerPostingUseCaseService.post()` — the single write entry point — so that all callers (REST API, Kafka consumer, batch jobs) trip the same breaker.

**Implementation:** `@CircuitBreaker` is on `LedgerEventProcessor.process()`, which is Kafka-only. REST callers hit `LedgerPostingController` → `LedgerPostingUseCaseService.post()` directly — no circuit breaker in that path.

**Impact:** A DB failure that trips the breaker stops Kafka processing (correct) but does not stop REST API callers. REST clients will hammer a degraded DB with no back-pressure, accelerating the failure while the Kafka consumer is correctly paused.

The `@CircuitBreaker` annotations on `JobsAdminController` are on the controller layer and use a fallback method — these will trip independently of the posting path breaker, which is not the intended design.

**Fix:** Move `@CircuitBreaker(name = "ledgerProcessing")` to `LedgerPostingUseCaseService.post()`. Remove it from `LedgerEventProcessor.process()` (the consumer should catch `CallNotPermittedException` from the use-case call and handle it there).

---

#### C-2 — `fallbackOnOpenCircuit` re-throws to the Kafka consumer

**Plan says:** "Do not throw unchecked exceptions to the Kafka consumer when the circuit is open. Handle gracefully — log, pause consumer, do not route to DLQ."

**Implementation:**
```java
public void fallbackOnOpenCircuit(ExternalLedgerEvent event, Throwable t) {
    LOG.warn("Circuit open or retries exhausted...");
    throw t instanceof RuntimeException ? (RuntimeException) t : new RuntimeException(t);
}
```

Re-throwing propagates out of `LedgerEventProcessor.process()` to `KafkaLedgerEventConsumer.consume()`, which catches it and re-throws again. The `DefaultErrorHandler` treats this as a retriable failure, exhausts its 3 attempts, then routes the message to DLQ.

**Impact:** Messages that should wait for the circuit to close (and be retried from the original Kafka offset) are instead permanently routed to DLQ. This is data loss — the event is never processed after recovery. The `CircuitBreakerLifecycleListener` does pause the consumer on OPEN, but the fallback fires *before* the listener receives the state transition event, creating a race window where 1–N messages are DLQ'd before the consumer pauses.

**Fix:** Fallback should NOT re-throw. It should log and return (or throw a specific non-retriable sentinel that the consumer catches and translates to a no-ack with no DLQ routing). Since `CircuitBreakerLifecycleListener` handles the pause, the fallback's role is only to absorb the exception cleanly.

---

### High

---

#### H-1 — Circuit breaker configuration deviates from plan

| Parameter | Plan | Implementation |
|---|---|---|
| `sliding-window-type` | `TIME_BASED` | `COUNT_BASED` (default) |
| `sliding-window-size` | `60` (seconds) | `20` (count) |
| `failure-rate-threshold` | `5%` | `50%` |
| `permitted-number-of-calls-in-half-open-state` | `10` | `3` |

**Impact of `failure-rate-threshold: 50%`:** The breaker does not open until 10 of the last 20 calls fail. In a 500/sec posting environment this means ~10 seconds of 50% failure rate before the breaker opens — up to 2,500 failed events routed to retry/DLQ before any protection kicks in. The plan's 5% threshold (TIME_BASED) trips after ~3 seconds and ~75 failures.

**Impact of `permitted-in-half-open: 3`:** Recovery probing with 3 calls is insufficient for 500/sec. A transient DB spike lasting >1s will cause all 3 probes to fail and flip back to OPEN, preventing recovery even after the DB is healthy.

**Fix:** Align with plan values. If the plan values are wrong for the actual SLA, document the deviation with rationale — do not silently diverge.

---

#### H-2 — `KafkaLagTracker` measures single-message staleness, not partition consumer lag

**Implementation:**
```java
long lagMs = Instant.now().toEpochMilli() - event.occurredAtEpochMs();
lagTracker.updateLagSeconds(lagMs / 1000);
```

This computes how long ago the *current message being processed* was produced. It is a per-message staleness metric, not a consumer lag metric.

**Plan requires:** "Alert if consumer lag exceeds 5 seconds." Real consumer lag = `(endOffset - committedOffset)` across all assigned partitions, queried from the broker. A single-message staleness value will not trigger the alert correctly:

- If no messages are flowing, the gauge stays at the last processed value indefinitely — it does not reflect that the consumer has fallen behind.
- If the partition has 10,000 unprocessed messages all produced 2 seconds ago, the gauge shows 2 seconds — appears fine even though true lag is significant.
- The gauge reflects `Instant.now() - event.occurredAtEpochMs()` which includes time-in-transit from producer + broker, not just consumer delay.

**Fix:** Inject `KafkaListenerEndpointRegistry` + a `AdminClient` (or use the built-in Micrometer Kafka binder's `kafka.consumer.records-lag-max` gauge). Alternatively, add a scheduled poller that calls `AdminClient.listConsumerGroupOffsets()` vs `listOffsets(endOffsets)` to compute true lag per partition, then expose the max as a gauge.

**Short-term stopgap:** Rename the current metric to `kafka_consumer_message_staleness_seconds` to avoid confusion with a true lag gauge.

---

#### H-3 — Read replica DataSource is not Spring-managed; no lag monitoring or fallback

**Implementation:** When `ledger.datasource.read-replica.enabled=true`, `ReadReplicaDataSourceConfig` constructs a `HikariDataSource` directly and wraps it in a `JdbcTemplate` bean. The `HikariDataSource` instance is not registered as a Spring bean.

**Consequences:**

1. **No actuator health check.** Spring Boot's `DataSourceHealthIndicator` only checks Spring-managed `DataSource` beans. A replica pool failure is invisible to `/actuator/health`.
2. **No HikariCP metrics.** Pool utilization, connection wait time, and timeout events are not registered with Micrometer because the pool is constructed outside Spring context.
3. **No graceful shutdown.** `HikariDataSource.close()` is never called on context shutdown — connections leak until the pool times out.
4. **No replica lag monitoring or fallback.** Plan requires: "if replica lag exceeds 500ms, fall back to primary." No lag metric (`read_replica_lag_ms`) is implemented. The routing is static — once enabled, all read queries always go to the replica regardless of lag.

**Fix:**
- Register the replica `HikariDataSource` as a `@Bean` separately so Spring manages its lifecycle.
- Add a `@Scheduled` poller that queries `SELECT EXTRACT(EPOCH FROM (now() - pg_last_xact_replay_timestamp()))` on the replica and updates a gauge. If lag > 500ms, set an `AtomicBoolean replicaHealthy = false` and have `readReplicaJdbcTemplate` delegate to primary when false.

---

### Medium

---

#### M-1 — Load tests do not cover the 500/sec full lifecycle scenario

**Plan requires:** "Sustained 500 orders/sec through the complete ORDER_CREATED → ORDER_FILL → ORDER_CANCELLED lifecycle for 10 minutes."

**Implementation:**
- `posting-load.js`: 50 virtual users, ramps to 50 req/s — covers only the REST posting endpoint, not full lifecycle.
- `balance-read-load.js`: 200 req/s — correct target for reads.
- `reconciliation-load.js`: 2 req/min — correct.

No script covers:
- ORDER_CREATED → ORDER_FILL → ORDER_CANCELLED via Kafka events (the primary write path at 500/sec).
- Concurrent deposit + withdrawal + order fill to stress the NSF guard and reservation logic under contention.
- Settlement job running concurrently with live postings (the deadlock risk).

**Fix:** Add a k6 scenario (or a JUnit load test using `@SpringBootTest` + virtual threads) that submits 500 Kafka messages/sec through the full order lifecycle. Add a concurrent scenario that runs settlement/reconciliation while postings are in flight.

---

#### M-2 — Missing circuit breaker runbook

**Plan requires 5 runbooks.** Only 4 are implemented:
- `reconciliation-mismatch.md` ✅
- `settlement-batch-recovery.md` ✅
- `consumer-lag-escalation.md` ✅
- `journal-invariant-incident.md` ✅
- **`circuit-breaker-open.md`** ❌ — missing

The circuit breaker runbook should cover: how to identify which state the breaker is in (`/actuator/circuitbreakers`), the decision tree for manual reset vs. waiting for half-open, how to drain the DLQ after recovery, and escalation criteria for when the breaker cycling indicates a systemic issue.

---

#### M-3 — Chaos tests are manual documentation, not automated

**Plan requires automated chaos test suite.** `load-and-chaos-validation.md` documents the scenarios correctly (DB failover, Kafka broker restart, connection pool saturation, clock skew) but all are described as manual steps.

**Impact:** Chaos tests that require manual execution will not be run regularly, and their pass/fail status is not tracked in CI. Regressions introduced in future phases will go undetected until production.

**Recommendation:** Integrate at least the DB failover and Kafka restart scenarios using Testcontainers' `GenericContainer.stop()` / `.start()` in a dedicated `@SpringBootTest` chaos test class. These can run in a separate Gradle task (`./gradlew chaosTest`) to avoid slowing the main test suite.

---

## Unresolved Issues Carried from Phase 4 (Pass 3)

These were reported in `docs/phase4-review.md` Pass 3 and remain open:

| # | Issue | Severity |
|---|---|---|
| P4-1 | `createRun()` not `@Transactional(REQUIRES_NEW)` → `failRun()` UPDATE finds no row and silently no-ops when outer transaction rolls back | High |
| P4-2 | `impacted_accounts` CTE uses `created_at >= ?` for scoping but `created_at` is not the partition key — full-table scan across all monthly partitions | Medium |
| P4-3 | Reconciliation issue fingerprint includes `detailsJson` (balance values) — dedup breaks when mismatch amount changes between runs | Medium |
| P4-4 | `completeRun(runId, 0, 0)` in `SettlementService` hardcodes mismatch and invariant violation counts as zero | Medium |
| P4-5 | FAILED batch resume not logged — if `findBatch` returns FAILED state, execution continues silently without warning | Low |
| P4-6 | All 6 Phase 4 required integration tests missing (`SettlementServiceIntegrationTest`, `ReconciliationServiceIntegrationTest`) | High |

---

## Required Tests Not Yet Written (Phase 5)

| Test | Description |
|---|---|
| `CircuitBreakerIntegrationTest.circuitOpensAfterFailureThreshold` | Force DB failures; assert circuit opens after threshold; assert Kafka consumer pauses |
| `CircuitBreakerIntegrationTest.circuitRecoveryAfterHalfOpen` | Assert consumer resumes after HALF_OPEN → CLOSED |
| `KafkaConsumerIntegrationTest.messageWithInvalidPartitionKeyGoesToDlq` | Send message with mismatched key; assert DLQ receives it with correct headers |
| `KafkaConsumerIntegrationTest.circuitOpenDoesNotRouteToDlq` | Open circuit manually; send message; assert it is NOT in DLQ (waits for recovery) |
| `ReadReplicaFallbackTest.replicaLagExceedsThresholdFallsBackToPrimary` | Simulate replica lag; assert queries route to primary |

---

## Checklist

| Plan Item | Status | Notes |
|---|---|---|
| Circuit breaker on posting path | ⚠️ Wrong layer | Applied to Kafka adapter, not use-case |
| Circuit breaker thresholds (5%, TIME_BASED, 60s) | ❌ | 50%, COUNT_BASED, 20 |
| Fallback: no DLQ on circuit open | ❌ | Fallback re-throws → DLQ |
| Kafka retry + DLQ | ✅ | Exponential backoff, DLQ headers correct |
| Consumer group ID `ledger-consumer-group` | ⚠️ | Configured as `ledger-consumers-v1` |
| Kafka consumer lag alert (> 5s) | ⚠️ | Gauge measures staleness, not partition lag |
| Circuit breaker → consumer pause/resume | ✅ | `CircuitBreakerLifecycleListener` |
| Read replica for balance + buying power queries | ✅ | `readReplicaJdbcTemplate` wired |
| Replica lag monitoring + fallback | ❌ | Not implemented |
| Read replica as Spring-managed bean | ❌ | Inline HikariDataSource — no health/metrics/shutdown |
| Load test at 500/sec full lifecycle | ❌ | 50 req/s REST-only |
| Balance read load test (p99 < 10ms) | ✅ | 200 req/s |
| Reconciliation load test (p99 < 5s) | ✅ | 2/min |
| Chaos test automation | ❌ | Manual steps only |
| 5 runbooks | ⚠️ | 4 of 5 (missing: circuit-breaker-open) |
| `SnapshotRebuildService` scheduled monthly | ❌ | No `@Scheduled` on rebuild trigger |
