# Account Ledger Implementation Plan

## 1) Goal and Scope
Build a production-grade, double-entry account ledger service for brokerage custody that supports:
- Deposits
- Buy/sell order lifecycle (hold, execute, release)
- T+2 settlement
- Withdrawals
- Real-time buying power and balance reads
- Full audit trail and reconciliation

Out of scope for v1:
- Order routing/exchange connectivity internals (consumed as events)
- KYC/onboarding
- FX/multi-currency conversion
- Portfolio valuation/P&L

## 2) Success Criteria (Interview-Aligned)
- No double-spend: all money movements validated with strong consistency.
- Immutable ledger: append-only journal entries with reversal-based corrections.
- Idempotent write flows: duplicate events do not create duplicate postings.
- Accurate buying power and account snapshots under peak load.
- Reconciliation detects and alerts on ledger vs snapshot vs bank mismatches.
- Auditability and retention suitable for 7-year regulatory needs.

## 3) Proposed Tech Stack (Java)
- Java 21
- Spring Boot 3.x
- PostgreSQL 15+ (primary source of truth)
- Redis (optional cache for read-heavy balance endpoints)
- Kafka or RabbitMQ (OMS/bank/exchange integration)
- Flyway (schema migrations)
- Testcontainers (integration tests)
- Micrometer + Prometheus + Grafana (metrics)
- OpenTelemetry (tracing)

## 4) Domain Model and Accounting Design
### Core account buckets (per customer/currency)
- `SETTLED_CASH`
- `UNSETTLED_CASH_SALES`
- `UNSETTLED_CASH_BUYS`
- `RESERVED_CASH`
- `SECURITIES` (or separate holdings model)

Broker-level accounts:
- `BROKERAGE_OMNIBUS`
- `BROKERAGE_CUSTODY_LIABILITY`

### Ledger principles
- Every event produces balanced debit/credit pair(s).
- `journal_entries` is immutable (`INSERT` only).
- `account_balances` is a derived snapshot for fast reads.
- Buying power is derived from snapshot balances (not persisted as a standalone value).

## 5) Architecture
- **Ledger API (sync):** read balances/buying power, initiate withdrawals/deposits where needed.
- **Ledger Event Consumer (async):** handles OMS/bank/exchange events with idempotent processing.
- **Ledger Posting Engine:** validates and writes debit/credit entries in a single DB transaction.
- **Snapshot Updater:** updates `account_balances` atomically with optimistic locking.
- **Settlement Scheduler:** daily T+2 settlement job with distributed lock.
- **Reconciliation Job:** compares ledger aggregate vs snapshot vs external bank statement.

## 6) Data Schema Plan
Implement via Flyway migrations.

### `accounts`
- `id`, `customer_id`, `type`, `currency`, timestamps
- Unique: `(customer_id, type, currency)`

### `journal_entries`
- `id`, `entry_group_id`, `account_id`, `direction`, `amount`, `currency`
- `event_type`, `reference_id`, `effective_date`, `created_at`
- `idempotency_key` (unique)
- Indexed by `(account_id, created_at)`, `(reference_id)`, `(effective_date)`

### `account_balances`
- `account_id` (PK), `balance`, `version`, `last_entry_id`, `updated_at`

### Optional support tables
- `processed_events` (if idempotency key not fully in `journal_entries`)
- `reconciliation_runs`, `reconciliation_issues`
- `settlement_batches`

## 7) Event Contract Plan
Define explicit versioned event contracts (`v1`) for:
- `VA_CREDITED` (deposit)
- `ORDER_CREATED` (hold)
- `ORDER_FILL` (execute partial/full)
- `ORDER_CANCELLED` / `ORDER_REJECTED` (release)
- `SETTLEMENT_CONFIRMED` / `SETTLEMENT_FAILED`
- `WITHDRAWAL_REQUESTED` / bank callback events

Each event must include:
- `eventId` (global unique)
- `eventType`
- `occurredAt`
- `customerId`
- `currency`
- `amount` and/or `quantity`
- `referenceId` (order/fill/settlement/transfer)

## 8) Service Modules (Code Organization)
Suggested package/module split:
- `ledger-domain`: entities, enums, accounting rules
- `ledger-application`: command handlers/use-cases
- `ledger-infrastructure`: JPA/JDBC repositories, messaging adapters, redis adapter
- `ledger-api`: controllers + DTO mapping
- `ledger-jobs`: settlement and reconciliation schedulers

Core use-cases:
- `postDeposit`
- `holdFundsForOrder`
- `executeOrderFill`
- `releaseOrderHold`
- `settleBuy`
- `settleSell`
- `initiateWithdrawal`
- `reverseFailedWithdrawal`
- `getBalances` / `getBuyingPower`

## 9) Implementation Phases

### Phase 0 — Foundation (Week 1)
- Initialize Spring Boot service skeleton.
- Configure PostgreSQL + Flyway + Testcontainers.
- Add structured logging, metrics, tracing scaffolding.
- Define coding conventions and error model.

Deliverable:
- Buildable project with health endpoint, DB connectivity, migration pipeline, CI test run.

### Phase 1 — Ledger Core and Posting Engine (Week 2)
- Implement account and journal schemas.
- Build posting engine enforcing balanced entries.
- Enforce idempotency keys on postings.
- Add transaction boundaries and optimistic locking for snapshots.

Deliverable:
- Reliable debit/credit posting with unit + integration coverage.

### Phase 2 — Order Lifecycle Flows (Week 3)
- Implement hold (`ORDER_CREATED`).
- Implement execute (`ORDER_FILL`) for partial/full fills.
- Implement release (`ORDER_CANCELLED`/`ORDER_REJECTED`) for unexecuted remainder only.
- Implement buy/sell semantics and buying power derivation.

Deliverable:
- End-to-end event-driven flow with exact expected money-state transitions.

### Phase 3 — Deposits and Withdrawals (Week 4)
- Implement deposit processing (`VA_CREDITED`).
- Implement withdrawal two-step hold + bank confirmation/failure reversal.
- Add API endpoints for balance and withdraw request status.

Deliverable:
- Cash-in/cash-out flows with compensating entries and idempotent retries.

### Phase 4 — Settlement and Reconciliation (Week 5)
- Implement daily T+2 settlement scheduler.
- Add distributed locking for single-run safety.
- Implement reconciliation job and discrepancy alerting policy.

Deliverable:
- Automated settlement and reconciliation reports with mismatch detection.

### Phase 5 — Hardening and Scale Validation (Week 6)
- Load tests at interview target (spike to 500 fill events/sec).
- Verify p99 write/read targets and lock-contention behavior.
- Tune indexes, DB pool, and batch consumption.
- Add runbooks for degraded modes and incident response.

Deliverable:
- Performance report + production readiness checklist.

## 10) API Plan (v1)
- `GET /v1/accounts/{customerId}/balances`
- `GET /v1/accounts/{customerId}/buying-power`
- `POST /v1/withdrawals` (idempotent request key)
- `GET /v1/withdrawals/{id}`
- `GET /v1/audit/journal?customerId=...&from=...&to=...`

Notes:
- Writes that originate from external systems should primarily come from events.
- API writes must also be idempotent and auditable.

## 11) Testing Strategy
- Unit tests for accounting rules and invariants.
- Integration tests for transactional posting + snapshot updates.
- Contract tests for incoming event schemas.
- End-to-end tests for full order lifecycle and failure scenarios.
- Concurrency tests for duplicate event delivery and race conditions.
- Property-based tests for ledger conservation invariants.

Critical assertions:
- Sum(debits) == Sum(credits) per entry group.
- Duplicate event does not mutate final balances.
- Cancellation releases only open/unexecuted reserved amount.
- Reversal entries preserve immutable history.

## 12) Observability and Operations
- Metrics:
  - `ledger_postings_total{eventType,status}`
  - `ledger_posting_latency_ms`
  - `idempotency_deduplications_total`
  - `reconciliation_mismatches_total`
  - `settlement_batch_duration_ms`
- Logs:
  - Structured logs with `eventId`, `referenceId`, `entryGroupId`, `customerId`
- Alerts:
  - Reconciliation mismatch > 0
  - Settlement job failure
  - Posting failure rate threshold

## 13) Risk Register and Mitigations
- Duplicate/out-of-order events: idempotency key + sequence handling per reference.
- Lock contention on hot accounts: partitioning and retry with jitter.
- Snapshot drift: periodic rebuild from ledger + reconciliation alarms.
- Partial external failures (bank/settlement): compensating journal entries.
- Schema evolution risk: versioned event contracts + backward compatibility policy.

## 14) Interview Demo Script (Optional)
Use this sequence in a live review/demo:
1. Deposit AED 10,000.
2. Place buy order hold AED 5,000.
3. Partial fill AED 3,000.
4. Cancel remainder AED 2,000 release.
5. Run T+2 settlement for filled amount.
6. Show balances, buying power, and immutable journal timeline.
7. Trigger duplicate fill event and show no double posting.

## 15) Delivery Checklist
- [ ] Flyway migrations and DB indexes applied.
- [ ] Posting engine enforces balancing and immutability.
- [ ] Idempotency implemented for all write paths.
- [ ] Lifecycle event consumers complete.
- [ ] Settlement scheduler and reconciliation job active.
- [ ] API read paths stable and documented.
- [ ] Load test report meets agreed targets.
- [ ] Runbooks and alerting in place.

---

## Recommended Next Build Steps
1. Implement Phase 0 and Phase 1 first with deep test coverage.
2. Build a deterministic fixture suite for lifecycle transitions before wiring all async integrations.
3. Add load testing early (end of Phase 2) to catch lock-contention and schema-index issues before full feature completion.
