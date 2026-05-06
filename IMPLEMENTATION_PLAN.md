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

---

## 2) Success Criteria
- No double-spend: all money movements validated with strong consistency.
- Immutable ledger: append-only journal entries with reversal-based corrections.
- Idempotent write flows: duplicate events do not create duplicate postings.
- Accurate buying power and account snapshots under peak load.
- Reconciliation detects and alerts on ledger vs. snapshot vs. bank mismatches.
- Auditability and retention suitable for 7-year regulatory needs.
- **p99 targets (must be validated in Phase 5):**
  - Posting write: < 50ms
  - Balance read: < 10ms
  - Reconciliation query (per customer): < 5s

---

## 3) Tech Stack
- Java 21 (virtual threads available for concurrency)
- Spring Boot 3.x
- PostgreSQL 16+ (primary source of truth)
- Redis (mandatory for balance read cache once Phase 3 lands — not optional)
- Kafka (event consumption from OMS/bank/exchange)
- Flyway (schema migrations)
- Testcontainers (integration tests — all integration tests must use real DB, no mocks)
- Micrometer + Prometheus + Grafana (metrics)
- OpenTelemetry (distributed tracing)
- Resilience4j (circuit breaker, Phase 5)

---

## 4) Domain Model and Accounting Design

### Core account types (per customer/currency)
- `SETTLED_CASH` — minimum balance: 0 (never negative)
- `UNSETTLED_CASH_SALES` — minimum balance: 0
- `UNSETTLED_CASH_BUYS` — minimum balance: 0
- `RESERVED_CASH` — minimum balance: 0 (never negative)
- `SETTLEMENT_PENDING` — minimum balance: 0

Broker-level accounts:
- `BROKERAGE_OMNIBUS` — no minimum balance constraint (net position)
- `BROKERAGE_CUSTODY_LIABILITY` — no minimum balance constraint

### Balance constraint rules
Each account type has an explicit minimum balance floor enforced at the application level before any snapshot delta is applied:
- If `balance + delta < floor`, reject with `InsufficientFundsException` before any DB write.
- This check runs inside the same transaction as the snapshot update.
- The `account_balances` table must also have a DB-level check for accounts where `balance >= 0` is required (enforced via a partial CHECK constraint or trigger).

### Ledger principles
- Every event produces balanced debit/credit pair(s): `SUM(DEBIT amounts) == SUM(CREDIT amounts)` per currency per entry group, enforced before any DB write.
- `journal_entries` is immutable (INSERT only). Corrections use reversal entries — never UPDATE or DELETE.
- `account_balances` is a derived snapshot for fast reads. If it diverges from `journal_entries`, it must be rebuildable via the snapshot rebuild utility.
- Buying power is derived at read time from snapshot balances — never persisted as a standalone value.
- All monetary amounts use `NUMERIC(20,8)` in the DB and `BigDecimal` in Java. `double` and `float` are forbidden for money.
- Rounding rule: round half-up to 8 decimal places at every arithmetic operation. Never truncate. Document the rule at the `BalancedPostingPolicy` level.
- All timestamps stored and compared in UTC. Customer timezone is UI-only.

### Idempotency key contract
- Format: `{eventType}-{referenceId}-{discriminator}` (e.g., `ORDER_HOLD-uuid-v1`, `ORDER_FILL-uuid-fillId`).
- The key must be stable across retries for the same logical event. The OMS/bank/exchange is contractually required to provide a stable idempotency key on retry.
- The `ledger_postings` table is the idempotency gate. One row per idempotency key covers the entire entry group (all legs).
- On conflict: validate that the stored `event_type` and `reference_id` match the incoming command. If they differ, reject with `IdempotencyKeyCollisionException` (distinct from a duplicate — this signals an application bug).
- The `journal_entries.idempotency_key` column is retained for traceability but is NOT the uniqueness gate (that moved to `ledger_postings` in V2). Do not re-add a unique constraint on it.

---

## 5) Architecture

- **Ledger API (sync):** read balances/buying power, initiate withdrawals/deposits where needed.
- **Ledger Event Consumer (async):** handles OMS/bank/exchange events with idempotent processing. Must have a DLQ and explicit retry policy (3 attempts with exponential backoff, then DLQ).
- **Ledger Posting Engine:** validates and writes debit/credit entries in a single DB transaction.
- **Snapshot Updater:** updates `account_balances` atomically using pessimistic row locking (`SELECT … FOR UPDATE`) — not optimistic locking (see Phase 1 constraints).
- **Settlement Scheduler:** daily T+2 settlement job using a dedicated connection pool and `SKIP LOCKED` for concurrent-safe batch processing.
- **Reconciliation Job:** runs every 4 hours; compares ledger aggregate vs. snapshot vs. external bank statement; validates journal invariants.
- **Snapshot Rebuild Utility:** streams `journal_entries` and rebuilds `account_balances` from scratch; must be non-blocking and runnable under live load.
- **Withdrawal Timeout Job:** scans `SETTLEMENT_PENDING` balances older than 48 hours and auto-reverses.

---

## 6) Data Schema Plan

All schema changes via Flyway migrations. Never use `ddl-auto: create` or `ddl-auto: update`.

### `accounts`
- `id UUID PK`, `customer_id UUID`, `type TEXT`, `currency TEXT DEFAULT 'AED'`, timestamps
- Unique: `(customer_id, type, currency)`
- Add `status TEXT NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'CLOSED'))` — posting engine must validate `status = 'ACTIVE'` before inserting legs.

### `journal_entries`
- `id UUID PK`, `entry_group_id UUID NOT NULL`, `account_id UUID FK accounts(id)`, `direction TEXT CHECK IN ('DEBIT','CREDIT')`, `amount NUMERIC(20,8) CHECK (amount > 0)`, `currency TEXT`, `event_type TEXT`, `reference_id UUID`, `effective_date DATE NOT NULL`, `created_at TIMESTAMPTZ NOT NULL DEFAULT now()`, `idempotency_key TEXT` (nullable, no unique constraint post-V2)
- Indexes: `(account_id, created_at)`, `(reference_id)`, `(effective_date)`, **`(entry_group_id)`** — required for reconciliation invariant checks.
- **Immutability:** A DB trigger (`BEFORE UPDATE OR DELETE ON journal_entries`) must raise an exception. This is enforced at the DB level, not just by application convention.
- **Partitioning:** Partition by `effective_date` (monthly `RANGE` partitioning). Add in Phase 4 before the table exceeds 10M rows.

### `account_balances`
- `account_id UUID PK FK accounts(id)`, `balance NUMERIC(20,8) NOT NULL DEFAULT 0`, `version BIGINT NOT NULL DEFAULT 0`, `last_entry_id UUID`, `updated_at TIMESTAMPTZ NOT NULL DEFAULT now()`
- **Locking strategy:** All updates use `SELECT … FOR UPDATE` (pessimistic), not optimistic version-compare. The `version` column is retained for audit/debugging but is not used for CAS retries.

### `ledger_postings`
- `idempotency_key TEXT PK`, `entry_group_id UUID NOT NULL`, `event_type TEXT NOT NULL`, `reference_id UUID`, `created_at TIMESTAMPTZ NOT NULL DEFAULT now()`
- Index: `(reference_id)`
- On INSERT conflict: `DO NOTHING`. After conflict, read back the row and validate `event_type + reference_id` match. If not, throw `IdempotencyKeyCollisionException`.

### `order_states`
- `reference_id UUID PK`, `state TEXT NOT NULL CHECK (state IN ('HOLD', 'PARTIALLY_FILLED', 'FILLED', 'CANCELLED', 'REJECTED'))`, `held_amount NUMERIC(20,8) NOT NULL`, `filled_amount NUMERIC(20,8) NOT NULL DEFAULT 0`, `currency TEXT NOT NULL`, `updated_at TIMESTAMPTZ NOT NULL DEFAULT now()`
- Used by the order state machine to validate event transitions and compute release amounts.

### `settlement_batches`
- `batch_id TEXT PK` (format: `settlement-{YYYY-MM-DD}`), `status TEXT CHECK IN ('PENDING','RUNNING','DONE','FAILED')`, `started_at TIMESTAMPTZ`, `completed_at TIMESTAMPTZ`, `entries_processed INT`, `created_at TIMESTAMPTZ NOT NULL DEFAULT now()`
- Idempotency gate for settlement: if `status = 'DONE'`, skip the batch entirely.

### `reconciliation_runs`
- `id UUID PK`, `run_at TIMESTAMPTZ`, `status TEXT`, `mismatches_found INT`, `notes TEXT`

### `reconciliation_issues`
- `id UUID PK`, `run_id UUID FK`, `account_id UUID`, `ledger_balance NUMERIC(20,8)`, `snapshot_balance NUMERIC(20,8)`, `bank_balance NUMERIC(20,8)`, `detected_at TIMESTAMPTZ`

### Optional support tables
- `withdrawal_requests` — tracks two-step withdrawal state with a `pending_since` timestamp for the timeout job.

---

## 7) Event Contract

Each event must include:
- `eventId` (global unique, stable on retry — used as or to derive the idempotency key)
- `eventType` (one of the defined types below)
- `occurredAt` (UTC ISO-8601)
- `customerId`
- `currency`
- `amount` and/or `quantity`
- `referenceId` (order/fill/settlement/transfer)

Defined event types:
- `VA_CREDITED` (deposit)
- `ORDER_CREATED` (hold)
- `ORDER_FILL` (execute partial/full; must include `fillId` for multi-fill idempotency)
- `ORDER_CANCELLED` (release unexecuted remainder only)
- `ORDER_REJECTED` (release full hold if no fills occurred)
- `SETTLEMENT_CONFIRMED`
- `SETTLEMENT_FAILED`
- `WITHDRAWAL_REQUESTED`
- `WITHDRAWAL_CONFIRMED` (bank ACK; must carry stable `callbackId`)
- `WITHDRAWAL_REJECTED` (bank NAK; reversal required)

**Backward compatibility rules:**
- Adding a new optional field to an event: non-breaking.
- Renaming a field, changing a field type, or removing a field: breaking — requires a new event version (`v2`) and a dual-consumer transition period.
- Never change the semantics of `eventId` or `referenceId` across versions.

---

## 8) Service Modules (Code Organization)

```
ledger/
  domain/
    model/         — PostLedgerEntriesCommand, PostLedgerEntriesResult, PostingLeg, EntryDirection
    service/       — BalancedPostingPolicy, OrderStateMachine, BuyingPowerPolicy
  application/
    port/in/       — LedgerPostingUseCase, LedgerBalanceQuery, OrderLifecycleUseCase, WithdrawalUseCase
    port/out/      — LedgerPostingPersistencePort, OrderStatePersistencePort, WithdrawalPersistencePort
    usecase/       — LedgerPostingUseCaseService, OrderLifecycleService, WithdrawalService
  adapter/
    in/web/        — LedgerPostingController, BalanceController, WithdrawalController
    in/messaging/  — KafkaEventConsumer (with DLQ routing)
    out/persistence/ — JdbcLedgerPostingAdapter, JdbcOrderStateAdapter, JdbcWithdrawalAdapter
platform/
  health/          — PingController
  jobs/            — SettlementScheduler, ReconciliationScheduler, WithdrawalTimeoutJob, SnapshotRebuildJob
shared/
  api/             — ApiError, GlobalExceptionHandler
  exception/       — InsufficientFundsException, IdempotencyKeyCollisionException, InvalidStateTransitionException
```

---

## 9) Implementation Phases

---

### Current Status Checkpoint (as of May 6, 2026)

- Phase 0 is complete (bootstrap, migrations, health endpoints, baseline tests).
- Phase 1 is in progress and implemented as a stable baseline:
  - Hexagonal structure in place (`domain`, `application` ports/use-cases, `adapter` layers).
  - Posting flow implemented with idempotency gate (`ledger_postings`) and balanced-entry validation.
  - Integration tests cover balanced posting, idempotency duplicate handling, and unbalanced rejection.
- We are intentionally not moving to Phase 2 until explicit confirmation.

---

### Phase 0 — Foundation (Week 1)

**Goal:** Buildable project with health endpoint, DB connectivity, Flyway migration pipeline, CI test run.

#### Tasks
1. Initialize Spring Boot service skeleton.
2. Configure PostgreSQL + Flyway + Testcontainers.
3. Add structured logging with MDC fields: `eventId`, `referenceId`, `entryGroupId`, `customerId`.
4. Add OpenTelemetry tracing with correlation ID propagated via MDC — must flow across Kafka consumer → use-case → persistence.
5. Expose `/actuator/health`, `/actuator/prometheus`.
6. Define error model (`ApiError`, `GlobalExceptionHandler`).

#### Constraints and Rules
- **HikariCP must be configured explicitly** in `application.yml`. Do not rely on defaults.
  ```yaml
  spring.datasource.hikari:
    maximum-pool-size: 30
    minimum-idle: 10
    connection-timeout: 10000       # 10s
    max-lifetime: 1800000           # 30min
    keepalive-time: 60000
  ```
- **p99 targets must be defined and documented** before any load test. Targets: posting < 50ms, balance read < 10ms, reconciliation query < 5s. Add to this file under Section 2.
- **Idempotency key format** must be documented and agreed on (see Section 4). Do not defer.
- **pgcrypto extension** — verify availability on the target PostgreSQL provider before committing to `gen_random_uuid()`. Add a smoke test that queries `pg_extension` for `pgcrypto`.

#### Migration Rollback Rules
- Every new Flyway migration must have a corresponding rollback test: simulate the migration failing mid-way (e.g., via a savepoint) and verify the DB is left in the prior state.
- V2 drops a unique constraint on `journal_entries.idempotency_key`. The rollback test must verify that re-adding the constraint does not fail if duplicate values were inserted after the drop.

#### Deliverables
- Buildable project with health endpoint, DB connectivity, Flyway migrations (V1, V2), CI passing.
- HikariCP config applied.
- Correlation ID visible in logs across a simulated request.

---

### Phase 1 — Ledger Core and Posting Engine (Week 2)

**Goal:** Reliable debit/credit posting with unit + integration coverage. All invariants enforced before Phase 2 begins.

#### Tasks
1. Implement `BalancedPostingPolicy` — enforce `SUM(DEBIT) == SUM(CREDIT)` per currency per entry group.
2. Implement `LedgerPostingUseCaseService.post()` — single write entry point, `@Transactional`.
3. Implement `JdbcLedgerPostingAdapter` — idempotency gate, journal insert, snapshot update.
4. Add account existence and active-status check before inserting any leg.
5. Add NSF guard on snapshot update.
6. Add snapshot rebuild utility.
7. Add immutability trigger on `journal_entries`.
8. Add migration V3 for `accounts.status`, `order_states`, immutability trigger, and `entry_group_id` index.

#### Constraints and Rules

**Posting Flow (must be followed exactly):**
1. `BalancedPostingPolicy.validate(command)` — throws `IllegalArgumentException` with message containing "unbalanced" if `SUM(DEBIT) != SUM(CREDIT)` per currency. No DB writes if this fails.
2. Validate all `accountId` values in legs exist in `accounts` table with `status = 'ACTIVE'`. Throw `AccountNotFoundException` or `AccountClosedException` if not. No DB writes if this fails.
3. `reserveIdempotencyOrGetExisting(idempotencyKey, entryGroupId, eventType, referenceId)`:
   - `INSERT INTO ledger_postings … ON CONFLICT (idempotency_key) DO NOTHING`.
   - If `inserted == 0` (conflict): read back the existing row and verify `event_type == command.eventType()` AND `reference_id == command.referenceId()`. If mismatch: throw `IdempotencyKeyCollisionException`. If match: return `PostLedgerEntriesResult(existingEntryGroupId, duplicate=true)`.
   - If `inserted == 1`: return `PostLedgerEntriesResult(newEntryGroupId, duplicate=false)`.
4. If `duplicate == true`: return immediately. No journal insert, no snapshot update.
5. `insertJournalEntries(entryGroupId, command)` — batch insert all legs. If any leg fails (e.g., FK violation on `account_id`), the transaction rolls back including the `ledger_postings` row.
6. `applySnapshotDeltas(entryGroupId, legs)` — for each leg, apply delta using **pessimistic locking**:
   ```sql
   SELECT balance FROM account_balances WHERE account_id = ? FOR UPDATE
   ```
   - If no row exists: `INSERT INTO account_balances(account_id, balance, ...) VALUES (?, ?, ...)`.
   - If row exists: validate NSF (see below), then `UPDATE account_balances SET balance = balance + ?, version = version + 1, ... WHERE account_id = ?`.
   - Do NOT use a version-compare CAS loop. `FOR UPDATE` serializes concurrent updates at the DB level.
7. Return `PostLedgerEntriesResult`.

**NSF Guard:**
- Before applying any delta, check: if `accountType` has `minBalance = 0` AND `currentBalance + delta < 0`: throw `InsufficientFundsException` with the account ID and the shortfall amount.
- Account types with `minBalance = 0`: `SETTLED_CASH`, `RESERVED_CASH`, `UNSETTLED_CASH_BUYS`, `UNSETTLED_CASH_SALES`, `SETTLEMENT_PENDING`.
- Account types with no floor: `BROKERAGE_OMNIBUS`, `BROKERAGE_CUSTODY_LIABILITY`.
- Implement this as an `AccountTypePolicy` lookup (map of `type → minBalance`) injected into the adapter. Do not hardcode inline.

**Snapshot Locking — No Optimistic Retry Loop:**
- Remove `applySingleBalanceDeltaWithOptimisticRetry()` entirely.
- Replace with a single `SELECT … FOR UPDATE` followed by a plain `UPDATE`. PostgreSQL will serialize concurrent writers on the same row.
- The `version` column is retained and incremented for auditability, but it is not used for conflict detection.

**Idempotency Key Validation:**
- When `reserveIdempotencyOrGetExisting` detects a conflict, it MUST compare `event_type` AND `reference_id` from the existing `ledger_postings` row against the incoming command.
- A match → duplicate (return existing `entry_group_id`, `duplicate=true`).
- A mismatch → collision (throw `IdempotencyKeyCollisionException`, log at ERROR with both keys).

**Immutability Trigger (add in V3 migration):**
```sql
CREATE OR REPLACE FUNCTION prevent_journal_mutation()
RETURNS TRIGGER AS $$
BEGIN
  RAISE EXCEPTION 'journal_entries is immutable: UPDATE and DELETE are forbidden';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER journal_entries_immutability
BEFORE UPDATE OR DELETE ON journal_entries
FOR EACH ROW EXECUTE FUNCTION prevent_journal_mutation();
```

**`entry_group_id` Index (add in V3 migration):**
```sql
CREATE INDEX IF NOT EXISTS idx_journal_entries_entry_group
    ON journal_entries (entry_group_id);
```

**Effective Date Validation:**
- `PostLedgerEntriesCommand` must reject `effectiveDate > LocalDate.now(ZoneOffset.UTC)` with `IllegalArgumentException`. Future-dated entries corrupt settlement queries.

**Snapshot Rebuild Utility:**
- Class: `SnapshotRebuildService` (in `platform/jobs/`).
- Algorithm: stream `journal_entries` ordered by `(account_id, created_at)`, accumulate per-account balance, write results to a staging table (`account_balances_rebuild`), compare with `account_balances`, log all discrepancies, then swap if discrepancy count = 0.
- Must be runnable without stopping live postings (use a separate connection, no table lock).
- Must be idempotent: if called twice, the second run produces the same result.

#### Required Tests
- `LedgerPostingServiceIntegrationTest` (existing) — all 3 cases must pass.
- **NSF test:** post a DEBIT of amount > current balance on a `SETTLED_CASH` account; assert `InsufficientFundsException` is thrown; assert `journal_entries` count = 0 and `ledger_postings` count = 0.
- **Account-closed test:** post to an account with `status = 'CLOSED'`; assert `AccountClosedException` is thrown; assert no journal rows.
- **Idempotency collision test:** post with idempotency key `K`, then post again with same key but different `referenceId`; assert `IdempotencyKeyCollisionException`.
- **Immutability test:** attempt `UPDATE journal_entries SET amount = 0 WHERE ...` via `JdbcTemplate`; assert a `DataAccessException` is thrown.
- **Concurrency test (50 threads, same account):** 50 threads each posting a valid balanced entry to the same account concurrently; assert zero exceptions; assert `balance == sum of all posted amounts × direction sign`.
- **Future effective date test:** post with `effectiveDate = LocalDate.now().plusDays(1)`; assert `IllegalArgumentException`.
- **Snapshot rebuild test:** insert 10 entries for an account, manually corrupt `account_balances.balance`, run `SnapshotRebuildService`, assert `account_balances.balance` is corrected.

#### Deliverables
- Posting engine with pessimistic locking, NSF guard, idempotency validation, immutability trigger.
- All 8 required tests passing.
- `SnapshotRebuildService` implemented and tested.

---

### Phase 2 — Order Lifecycle Flows (Week 3)

**Goal:** End-to-end event-driven order flow with exact money-state transitions and state machine enforcement.

#### Tasks
1. Implement order state machine (`OrderStateMachine`).
2. Implement `ORDER_CREATED` → hold (`RESERVED_CASH` debit, `SETTLED_CASH` credit).
3. Implement `ORDER_FILL` → execute (partial or full fill accounting).
4. Implement `ORDER_CANCELLED` / `ORDER_REJECTED` → release unexecuted remainder only.
5. Implement buying power derivation at read time.
6. Implement hold expiry job.

#### Order State Machine

States: `HOLD → PARTIALLY_FILLED → FILLED | CANCELLED | REJECTED`

Valid transitions:
- `(none) + ORDER_CREATED → HOLD`
- `HOLD + ORDER_FILL (partial) → PARTIALLY_FILLED`
- `HOLD + ORDER_FILL (full) → FILLED`
- `PARTIALLY_FILLED + ORDER_FILL (fills remainder) → FILLED`
- `HOLD + ORDER_CANCELLED → CANCELLED`
- `PARTIALLY_FILLED + ORDER_CANCELLED → CANCELLED`
- `HOLD + ORDER_REJECTED → REJECTED`

Any other transition must throw `InvalidStateTransitionException` and reject the posting with no DB writes.

**Enforcement rules:**
- Before processing any order event, read `order_states` row for `reference_id` with `SELECT … FOR UPDATE`.
- If row does not exist and event is `ORDER_CREATED`: create the row.
- If row does not exist and event is anything else: throw `InvalidStateTransitionException` (no hold to operate on).
- If event is `ORDER_FILL` and state is `CANCELLED` or `REJECTED` or `FILLED`: throw `InvalidStateTransitionException`.
- If event is `ORDER_CANCELLED` or `ORDER_REJECTED` and state is already `CANCELLED`, `REJECTED`, or `FILLED`: treat as idempotent no-op (already released); log at WARN and return.

**Partial Fill and Release Amount Rules:**
- On `ORDER_FILL`: `release_amount = fill_amount`. `filled_amount += fill_amount`. If `filled_amount == held_amount`: transition to `FILLED`.
- On `ORDER_CANCELLED`: `release_amount = held_amount - filled_amount`. This is the only valid release amount. If the event provides a release amount, validate it equals the computed remainder; if not, throw `IllegalArgumentException`.
- On `ORDER_REJECTED`: if `filled_amount == 0`, `release_amount = held_amount` (full release). If `filled_amount > 0`, treat as `ORDER_CANCELLED` (release remainder only).

**Rounding Rules:**
- All intermediate arithmetic uses `BigDecimal` with `MathContext.DECIMAL128`.
- Round the final amount to 8 decimal places using `RoundingMode.HALF_UP` before passing to the posting engine.
- If fill amounts do not sum exactly to held amount due to rounding: the last fill or the cancellation release absorbs the residual. Document this in `OrderLifecycleService`.

**Multi-Fill Idempotency:**
- Each `ORDER_FILL` event must carry a `fillId` (UUID). The idempotency key for a fill posting is: `ORDER_FILL-{referenceId}-{fillId}`.
- If the OMS retries a fill with a new UUID (i.e., a fresh `fillId`), the ledger will process it as a new fill. This is a contract violation by the OMS — document it as an external dependency requirement.

**Buying Power Derivation:**
- `buyingPower = SETTLED_CASH.balance + UNSETTLED_CASH_SALES.balance - UNSETTLED_CASH_BUYS.balance - RESERVED_CASH.balance`
- Read all four balances in a single SQL query with `REPEATABLE READ` isolation to avoid non-atomic reads:
  ```sql
  SELECT account_id, balance FROM account_balances
  WHERE account_id IN (?, ?, ?, ?)
  FOR SHARE
  ```
- Never compute buying power from four separate queries. Never cache buying power independently — always derive from snapshot balances.
- Buying power must never be negative. If the formula produces a negative result, return 0 and log at WARN.

**Hold Expiry Job:**
- Scan `order_states` for rows with `state = 'HOLD' OR state = 'PARTIALLY_FILLED'` and `updated_at < now() - interval '24 hours'`.
- For each: trigger an `ORDER_CANCELLED` reversal posting with release amount = `held_amount - filled_amount`.
- Use a distributed lock (e.g., `pg_try_advisory_lock`) so only one pod runs the job.
- Must be idempotent: if the order was already cancelled between the scan and the update, the row version check (via `SELECT … FOR UPDATE`) will detect the state change and skip.

#### Required Tests
- **State machine test — ORDER_FILL after ORDER_CANCELLED:** post `ORDER_CREATED`, then `ORDER_CANCELLED`, then `ORDER_FILL`; assert `InvalidStateTransitionException` on the fill; assert balances unchanged from post-cancel state.
- **Partial fill + cancel remainder test:** hold 5000, fill 3000, cancel; assert released amount = 2000; assert `RESERVED_CASH.balance = 0`.
- **Over-fill test:** hold 5000, fill 3000, fill 3000 again; assert second fill throws `IllegalArgumentException` (fill exceeds remaining hold).
- **Concurrent fill + cancel test:** 10 threads simultaneously posting fills and a cancel on the same order; assert final `RESERVED_CASH.balance >= 0` and total `filled_amount + release_amount == held_amount`.
- **Buying power atomicity test:** simulate a hold being placed mid-read; assert buying power is never overstated vs. the minimum of two consistent reads.
- **Idempotent cancel test:** post `ORDER_CANCELLED` twice for the same order; assert second invocation is a no-op with no extra journal entries.

---

### Phase 3 — Deposits and Withdrawals (Week 4)

**Goal:** Cash-in/cash-out flows with compensating entries, timeout handling, and idempotent retries.

#### Tasks
1. Implement `VA_CREDITED` (deposit) handler.
2. Implement withdrawal two-step: hold → bank confirmation/failure → release or reversal.
3. Implement withdrawal timeout job.
4. Add API endpoints for balance read and withdrawal request status.

#### Deposit Rules
- Idempotency key: `VA_CREDITED-{eventId}`.
- Posting: `BROKERAGE_OMNIBUS DEBIT, SETTLED_CASH CREDIT` for the deposit amount.
- No pre-validation of balance needed for deposits (credits only).
- After posting, emit an internal event to schedule a bank statement reconciliation check within 1 hour. (Reconciliation verifies the deposit against the actual bank wire.)

#### Withdrawal Two-Step Rules

**Step 1 — `WITHDRAWAL_REQUESTED`:**
- Idempotency key: `WITHDRAWAL_REQUESTED-{withdrawalId}`.
- Pre-check: `SETTLED_CASH.balance >= withdrawalAmount`. If not, throw `InsufficientFundsException` (do not post).
- Pre-check is performed inside a `SELECT … FOR UPDATE` on `account_balances` for `SETTLED_CASH` so no concurrent withdrawal can race between the check and the posting.
- Posting: `SETTLED_CASH DEBIT, SETTLEMENT_PENDING CREDIT`.
- Insert a `withdrawal_requests` row: `(withdrawalId, status='PENDING', amount, currency, pending_since=now())`.

**Step 2a — `WITHDRAWAL_CONFIRMED` (bank ACK):**
- Idempotency key: `WITHDRAWAL_CONFIRMED-{callbackId}`. The `callbackId` must be a stable identifier provided by the bank on every retry of the same confirmation.
- Posting: `SETTLEMENT_PENDING DEBIT, BROKERAGE_OMNIBUS CREDIT`.
- Update `withdrawal_requests.status = 'CONFIRMED'`.

**Step 2b — `WITHDRAWAL_REJECTED` (bank NAK):**
- Idempotency key: `WITHDRAWAL_REJECTED-{callbackId}`.
- Posting (reversal): `SETTLEMENT_PENDING DEBIT, SETTLED_CASH CREDIT`.
- Update `withdrawal_requests.status = 'REJECTED'`.

**Withdrawal Timeout Job:**
- Run every hour.
- Query: `SELECT * FROM withdrawal_requests WHERE status = 'PENDING' AND pending_since < now() - interval '48 hours' FOR UPDATE SKIP LOCKED`.
- For each: post the reversal entry (same as `WITHDRAWAL_REJECTED`), set `status = 'TIMED_OUT'`, emit an alert metric `ledger_withdrawal_timeout_total`.
- Use distributed lock (`pg_try_advisory_lock(12345)`) to ensure single-pod execution.
- The reversal posting is idempotent: idempotency key `WITHDRAWAL_TIMEOUT-{withdrawalId}`.

**Deposit/Withdrawal Concurrent-Safety Rule:**
- The pre-balance check for withdrawals (`SETTLED_CASH.balance >= amount`) is performed inside the transaction with `SELECT … FOR UPDATE`. This prevents a concurrent deposit or withdrawal from changing the balance between the check and the debit.
- A deposit that arrives while a withdrawal is in-flight does not require special handling — the `FOR UPDATE` on `account_balances` ensures the deposit's credit is serialized after or before the withdrawal's debit.

#### Required Tests
- **Deposit idempotency test:** post `VA_CREDITED` twice with same `eventId`; assert only one set of journal entries; assert `SETTLED_CASH.balance` reflects only one deposit.
- **Withdrawal happy path test:** request withdrawal, confirm; assert `SETTLED_CASH` debited, `SETTLEMENT_PENDING` zeroed, `BROKERAGE_OMNIBUS` credited.
- **Withdrawal rejection test:** request withdrawal, reject; assert `SETTLED_CASH` restored, `SETTLEMENT_PENDING` zeroed.
- **NSF withdrawal test:** request withdrawal exceeding balance; assert `InsufficientFundsException`; assert no journal entries.
- **Timeout job test:** insert a `withdrawal_requests` row with `pending_since = now() - 49 hours`, run the timeout job; assert reversal entries posted and `status = 'TIMED_OUT'`.
- **Concurrent withdrawal + deposit test:** simultaneously post a deposit and a withdrawal for the same customer in 20 threads; assert `SETTLED_CASH.balance >= 0` always; assert total journal entries consistent with number of accepted operations.
- **Double bank confirmation test:** post `WITHDRAWAL_CONFIRMED` twice with same `callbackId`; assert second is a no-op (idempotent).

---

### Phase 4 — Settlement and Reconciliation (Week 5)

**Goal:** Automated T+2 settlement and multi-frequency reconciliation with mismatch detection and journal invariant validation.

#### Tasks
1. Implement T+2 settlement scheduler.
2. Implement reconciliation job (every 4 hours).
3. Add journal invariant validation to reconciliation.
4. Add `journal_entries` range partitioning by `effective_date`.
5. Implement snapshot rebuild as a scheduled monthly job.

#### Settlement Rules

**Batch Idempotency:**
- Batch ID format: `settlement-{YYYY-MM-DD}` (UTC date of settlement, i.e., trade date + 2 days).
- Before processing: check `settlement_batches` table. If `status = 'DONE'`: skip entirely (return immediately).
- If `status = 'RUNNING'`: the previous run crashed. Resume from the last committed checkpoint (use the `entries_processed` count plus `OFFSET` in the query). Do not reprocess already-settled entries.
- Insert `settlement_batches` row with `status = 'PENDING'` at job start. Update to `'RUNNING'` before processing. Update to `'DONE'` only after all entries are committed.

**Settlement Query:**
- Select all `journal_entries` where `effective_date = {tradeDate}` and `event_type IN ('ORDER_FILL')`.
- Before settling, validate that the corresponding `order_states.state IN ('FILLED', 'CANCELLED', 'REJECTED')`. If any order is still in state `HOLD` or `PARTIALLY_FILLED`: skip that order, log at WARN, and add it to a `reconciliation_issues` entry for manual review.

**Settlement Posting:**
- For each filled trade: post the T+2 settlement entries (e.g., `UNSETTLED_CASH_BUYS DEBIT, SETTLED_CASH CREDIT` for a buy fill).
- Idempotency key per entry: `SETTLEMENT-{batchId}-{journalEntryId}`.

**Settlement Connection Pool:**
- Settlement job must use a dedicated `DataSource` bean (annotated `@Qualifier("settlementDataSource")`) with a separate HikariCP pool (`maximum-pool-size: 10`). It must NOT use the main write pool.
- Use `SELECT … FOR UPDATE SKIP LOCKED` on `journal_entries` for concurrent-safe batch picking.

**Timezone Rule:**
- Settlement date = `effective_date` from the journal entry (always UTC date). Never use server local time or customer timezone to determine the settlement date.

#### Reconciliation Rules

**Frequency:** Every 4 hours (not daily). Cron: `0 0 */4 * * *`.

**Checks to perform in each run:**

1. **Snapshot vs. ledger balance check:**
   - For each `account_id`, compare `account_balances.balance` vs. `SUM(amount × (CASE direction WHEN 'DEBIT' THEN 1 ELSE -1 END)) FROM journal_entries WHERE account_id = ?`.
   - Mismatch tolerance: exactly 0 (financial systems do not tolerate approximations).
   - On mismatch: insert `reconciliation_issues` row; emit `reconciliation_mismatches_total` metric; do NOT auto-correct (require manual review or snapshot rebuild approval).

2. **Journal invariant check:**
   - For each `entry_group_id`, assert `SUM(amount WHERE direction='DEBIT') == SUM(amount WHERE direction='CREDIT')` per currency.
   - Query:
     ```sql
     SELECT entry_group_id, currency,
            SUM(CASE direction WHEN 'DEBIT' THEN amount ELSE 0 END) AS total_debit,
            SUM(CASE direction WHEN 'CREDIT' THEN amount ELSE 0 END) AS total_credit
     FROM journal_entries
     GROUP BY entry_group_id, currency
     HAVING SUM(CASE direction WHEN 'DEBIT' THEN amount ELSE 0 END)
         != SUM(CASE direction WHEN 'CREDIT' THEN amount ELSE 0 END)
     ```
   - Any result row is a Critical alert: emit `reconciliation_journal_invariant_violation_total`; page on-call immediately.

3. **Bank statement check (daily only, not every 4 hours):**
   - Compare `SUM(SETTLED_CASH.balance per customer)` vs. bank-reported total. Mismatch → `reconciliation_issues`.

**Reconciliation Connection Pool:**
- Use the main read pool (can share with balance reads) but set `transaction_isolation = REPEATABLE READ` for the duration of each reconciliation query.

#### Table Partitioning
- Add migration V5: convert `journal_entries` to range partition by `effective_date` (monthly).
- All existing queries must be updated to include `effective_date` in WHERE clauses to enable partition pruning.
- Partition retention: keep hot partitions (< 1 year) on primary storage; archive older partitions to cold storage or a read-only replica.

#### Required Tests
- **Settlement idempotency test:** run settlement batch for date D twice; assert journal entries count is identical after both runs; assert `settlement_batches.status = 'DONE'` after first run.
- **Settlement with post-T cancellation test:** fill order on Day 0, cancel 30% on Day 1, run settlement on Day 2; assert only 70% of the amount is settled.
- **Settlement skip unfinished orders test:** insert an order in state `PARTIALLY_FILLED` with `effective_date = today - 2`; run settlement; assert the order is skipped and a `reconciliation_issues` row is created.
- **Journal invariant detection test:** manually insert an unbalanced `entry_group_id` (DEBIT 100, CREDIT 90); run reconciliation; assert a `reconciliation_issues` row is created and `reconciliation_journal_invariant_violation_total` metric is incremented.
- **Snapshot vs. ledger mismatch test:** manually update `account_balances.balance` to a wrong value; run reconciliation; assert mismatch detected and logged.
- **Timezone test:** insert a fill with `effective_date = '2025-01-15'`; run settlement with batch ID `settlement-2025-01-15`; assert the entry is included regardless of server timezone.

---

### Phase 5 — Hardening and Scale Validation (Week 6)

**Goal:** Validate 500 fill events/sec with p99 targets, failure resilience, and production readiness.

#### Tasks
1. Load test at 500 fill events/sec (Gatling or k6).
2. Chaos test scenarios.
3. Add Resilience4j circuit breaker.
4. Deploy read replica and route reads.
5. Add Kafka consumer lag alerting.
6. Finalize runbooks.

#### Load Test Requirements
- Tool: Gatling or k6.
- Scenario: simultaneous `ORDER_CREATED → ORDER_FILL → ORDER_CANCELLED` lifecycle, 500 orders/sec sustained for 10 minutes.
- Assertions (all must pass):
  - p99 posting latency < 50ms.
  - p99 balance read latency < 10ms.
  - Zero `IllegalStateException` errors.
  - Zero `InsufficientFundsException` errors (unless deliberately injected).
  - `ledger_postings` row count == number of unique events submitted (idempotency confirmed).
  - `SUM(DEBIT) == SUM(CREDIT)` across all entry groups at end of test (journal invariant holds).

#### Chaos Test Scenarios (must all be executed and passed)
1. **DB failover:** kill primary PostgreSQL mid-test; assert service recovers within 30s on replica promotion; assert no duplicate postings after recovery.
2. **Kafka broker restart:** restart one Kafka broker mid-test; assert no message loss (consumer resumes from last committed offset); assert no duplicate postings (idempotency gate holds).
3. **Connection pool saturation:** reduce HikariCP pool to 5 connections during load test; assert circuit breaker opens and fast-fails within 60s; assert pool recovers after load reduces.
4. **Settlement job + live postings:** run settlement job concurrently with 200 fills/sec; assert live posting p99 < 100ms (degradation < 2×); assert settlement completes correctly.

#### Circuit Breaker (Resilience4j)
- Apply to `LedgerPostingUseCaseService.post()`.
- Config: open if failure rate > 5% in a 60s sliding window; wait 30s in OPEN state; allow 10 probe calls in HALF-OPEN.
- On OPEN: return `HTTP 503` with `Retry-After: 30` header. Do not throw unchecked exceptions to the Kafka consumer (handle gracefully and pause consumer).
- Metric: `resilience4j_circuitbreaker_state` exposed on `/actuator/prometheus`.

#### Kafka Consumer Requirements
- Consumer group: `ledger-consumer-group`.
- Partition strategy: partition by `customerId` hash to ensure ordering per customer.
- `max.poll.records: 100` (do not consume faster than the posting engine can process).
- `isolation.level: read_committed` (transactional producers only).
- DLQ topic: `ledger-events-dlq`. Route messages that fail after 3 retries with exponential backoff (1s, 4s, 16s).
- Alert: if consumer lag exceeds 5 seconds, emit `kafka_consumer_lag_seconds` metric and fire a PagerDuty alert.

#### Read Replica
- Route `LedgerBalanceQuery.getAccountBalance()` and all reconciliation queries to the read replica `DataSource` (annotated `@Qualifier("readReplicaDataSource")`).
- Monitor replica lag: if lag > 500ms, fall back to the primary for balance reads and emit `read_replica_lag_ms` metric.

#### Runbooks (must exist before Phase 5 sign-off)
- **Reconciliation mismatch:** steps to investigate, trigger snapshot rebuild, escalate.
- **Settlement job failure:** steps to resume from checkpoint, validate idempotency, escalate.
- **Circuit breaker open:** steps to diagnose, manually reset, escalate.
- **Withdrawal stuck in PENDING:** steps to manually trigger timeout reversal.
- **Consumer lag > SLA:** steps to scale consumers, identify hot partitions, escalate.

---

## 10) API Plan (v1)

- `GET /v1/accounts/{customerId}/balances` — returns per-account balances; reads from read replica.
- `GET /v1/accounts/{customerId}/buying-power` — derives buying power from 4 snapshot balances using `REPEATABLE READ`.
- `POST /v1/withdrawals` (idempotent by `Idempotency-Key` header)
- `GET /v1/withdrawals/{id}`
- `GET /v1/audit/journal?customerId=...&from=...&to=...` — paginated, indexed by `(account_id, effective_date)`.

Notes:
- All write endpoints require an `Idempotency-Key` header (validated as non-empty UUID).
- Writes originating from external systems primarily come through Kafka events.
- API writes (e.g., `POST /v1/withdrawals`) use the same posting engine and idempotency gate.

---

## 11) Testing Strategy

### Test Categories
| Category | Tool | Scope |
|---|---|---|
| Unit | JUnit 5 | Accounting rules, state machine, rounding |
| Integration | JUnit 5 + Testcontainers | Full posting flow, snapshot, idempotency |
| Concurrency | JUnit 5 + `CountDownLatch` | Race conditions on same account |
| Contract | Pact / JSON Schema | Incoming event schemas |
| End-to-end | Testcontainers + Kafka | Full order lifecycle |
| Load | Gatling or k6 | 500 fills/sec for 10 min |
| Chaos | Manual + Gatling | DB failover, Kafka restart |

### Critical Assertions (must be present in integration tests)
- `SUM(DEBIT) == SUM(CREDIT)` per entry group per currency.
- Duplicate event does not mutate final balances (idempotency).
- Cancellation releases exactly `held - filled` amount (no more, no less).
- Reversal entries preserve immutable history (original entries still exist).
- `SETTLED_CASH.balance >= 0` at all times (NSF guard holds).
- `journal_entries` cannot be updated or deleted (immutability trigger fires).

### No Mock DB Rule
- All integration tests use real PostgreSQL via Testcontainers.
- No `@MockBean` for `DataSource`, `JdbcTemplate`, or any repository.
- Unit tests may mock only pure domain services (no infrastructure dependencies).

---

## 12) Observability

### Metrics
- `ledger_postings_total{eventType,status}` — counter
- `ledger_posting_latency_ms` — histogram (p50, p99)
- `idempotency_deduplications_total{eventType}` — counter
- `idempotency_key_collision_total` — counter (alert if > 0)
- `reconciliation_mismatches_total` — counter (alert if > 0)
- `reconciliation_journal_invariant_violation_total` — counter (page if > 0)
- `settlement_batch_duration_ms` — histogram
- `ledger_withdrawal_timeout_total` — counter
- `read_replica_lag_ms` — gauge
- `kafka_consumer_lag_seconds` — gauge (alert if > 5)
- `resilience4j_circuitbreaker_state` — gauge

### Structured Log Fields (MDC — required on every log line)
- `eventId`, `referenceId`, `entryGroupId`, `customerId`, `traceId`, `spanId`

### Alerts
| Alert | Condition | Severity |
|---|---|---|
| Reconciliation mismatch | `reconciliation_mismatches_total > 0` | High |
| Journal invariant violated | `reconciliation_journal_invariant_violation_total > 0` | Critical (page) |
| Settlement job failure | `status = 'FAILED'` in `settlement_batches` | Critical (page) |
| Posting failure rate | `error rate > 1% over 5m` | High |
| Circuit breaker open | `resilience4j_circuitbreaker_state = OPEN` | High |
| Withdrawal timeout | `ledger_withdrawal_timeout_total > 0` | Medium |
| Kafka lag | `kafka_consumer_lag_seconds > 5` | High |
| Idempotency key collision | `idempotency_key_collision_total > 0` | High |

---

## 13) Risk Register

| Risk | Mitigation |
|---|---|
| Duplicate/out-of-order events | Idempotency gate (`ledger_postings`); order state machine rejects invalid transitions |
| Lock contention on hot accounts | Pessimistic `SELECT … FOR UPDATE`; dedicated Kafka partition per customer |
| Snapshot drift | Reconciliation every 4 hours; snapshot rebuild utility |
| Settlement batch crash mid-run | Settlement batch idempotency (`settlement_batches` checkpoint); resume from `entries_processed` offset |
| Bank callback with new idempotency key | Require stable `callbackId` in bank event contract; document as external dependency |
| OMS retry with new fill UUID | Require stable `fillId` in OMS event contract; document as external dependency |
| Negative balance due to NSF gap | NSF guard inside `SELECT … FOR UPDATE` in the same transaction as the debit |
| Journal corruption | Immutability trigger; reconciliation journal invariant check |
| Over-release on cancel | Release amount = `held - filled`, computed from `order_states`, not from the event payload |
| Withdrawal stuck in PENDING | Withdrawal timeout job (48h TTL, idempotent reversal) |
| Table scan at scale | `journal_entries` monthly partitioning; `entry_group_id` index |
| Schema evolution breaking old consumers | Backward compatibility policy (additive-only for non-breaking changes; new event version for breaking) |

---

## 14) Delivery Checklist

### Phase 0
- [ ] HikariCP configured explicitly in `application.yml`
- [ ] p99 latency targets documented
- [ ] Idempotency key format contract documented
- [ ] Correlation ID propagation via MDC across all flows
- [ ] Migration rollback tests for V1 and V2

### Phase 1
- [ ] Pessimistic locking (`SELECT … FOR UPDATE`) replaces optimistic retry loop
- [ ] NSF guard implemented and tested
- [ ] Idempotency key collision detection implemented and tested
- [ ] Immutability trigger on `journal_entries`
- [ ] `entry_group_id` index on `journal_entries`
- [ ] Account active-status check before posting
- [ ] Future effective-date rejection
- [ ] Snapshot rebuild utility implemented and tested
- [ ] All 8 Phase 1 required tests passing

### Phase 2
- [ ] Order state machine with `order_states` table
- [ ] Release amount validated against `held - filled` (not event payload)
- [ ] Rounding policy implemented and documented
- [ ] Buying power derived atomically with `REPEATABLE READ`
- [ ] Hold expiry job implemented and tested
- [ ] All Phase 2 required tests passing

### Phase 3
- [ ] Deposit idempotency tested
- [ ] Withdrawal pre-check inside `FOR UPDATE` transaction
- [ ] Withdrawal timeout job implemented and tested
- [ ] Bank callback `callbackId` idempotency enforced
- [ ] All Phase 3 required tests passing

### Phase 4
- [ ] Settlement batch idempotency with `settlement_batches` checkpoint
- [ ] Pre-settlement order state validation
- [ ] Settlement uses dedicated connection pool with `SKIP LOCKED`
- [ ] Reconciliation runs every 4 hours
- [ ] Journal invariant check in reconciliation
- [ ] `journal_entries` partitioned by `effective_date`
- [ ] All Phase 4 required tests passing

### Phase 5
- [ ] Load test at 500 fills/sec passes all assertions
- [ ] All 4 chaos test scenarios pass
- [ ] Circuit breaker configured and tested
- [ ] Kafka DLQ and consumer lag alert configured
- [ ] Read replica deployed and routing applied
- [ ] All 5 runbooks written and reviewed

---

## 15) Demo Sequence

1. Deposit AED 10,000 → verify `SETTLED_CASH = 10,000`.
2. Place buy order hold AED 5,000 → verify `SETTLED_CASH = 5,000`, `RESERVED_CASH = 5,000`.
3. Partial fill AED 3,000 → verify `RESERVED_CASH = 2,000`, `UNSETTLED_CASH_BUYS = 3,000`.
4. Cancel remainder AED 2,000 → verify `RESERVED_CASH = 0`, `SETTLED_CASH = 7,000`.
5. Run T+2 settlement for AED 3,000 fill → verify `UNSETTLED_CASH_BUYS = 0`, `SETTLED_CASH = 7,000` (cash already debited at fill time; securities settled separately).
6. Show buying power derivation = 7,000.
7. Trigger duplicate fill event (same idempotency key) → show `duplicate=true`, no balance change.
8. Trigger fill on a cancelled order → show `InvalidStateTransitionException`.
9. Run reconciliation → show `reconciliation_runs` row with `mismatches_found = 0`.
