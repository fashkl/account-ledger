# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Commands

```bash
# Start local database
docker compose up -d

# Build
./gradlew build

# Run all tests (uses Testcontainers — requires Docker)
./gradlew test

# Run a single test class
./gradlew test --tests "com.mohamedali.ledger.ledger.LedgerPostingServiceIntegrationTest"

# Run a single test method
./gradlew test --tests "com.mohamedali.ledger.ledger.LedgerPostingServiceIntegrationTest.postIsIdempotentByKey"

# Run the application (requires DB_URL, DB_USER, DB_PASSWORD or local postgres on :5432)
./gradlew bootRun
```

All integration tests spin up a Testcontainers PostgreSQL container automatically; no local DB is needed to run tests.

## Architecture

The service is a **double-entry accounting ledger** for brokerage custody built with Spring Boot 3 / Java 21 / PostgreSQL. It follows **hexagonal architecture (ports and adapters)**.

### Package layout

```
ledger/
  domain/
    model/          — PostLedgerEntriesCommand, PostLedgerEntriesResult, PostingLeg, EntryDirection
    service/        — BalancedPostingPolicy (accounting invariant enforcement)
  application/
    port/in/        — LedgerPostingUseCase, LedgerBalanceQuery (inbound ports)
    port/out/       — LedgerPostingPersistencePort (outbound port)
    usecase/        — LedgerPostingUseCaseService (orchestrator)
  adapter/
    in/web/         — LedgerPostingController, DTOs
    out/persistence/ — JdbcLedgerPostingAdapter (raw JDBC, no ORM)
platform/
  health/           — PingController
shared/
  api/              — ApiError, GlobalExceptionHandler
```

### Posting flow

1. `LedgerPostingUseCaseService.post()` is the single write entry point (always `@Transactional`).
2. `BalancedPostingPolicy` validates that `sum(debits) == sum(credits)` per entry group before any DB write.
3. Idempotency is enforced via `ledger_postings.idempotency_key` (unique PK) using `INSERT … ON CONFLICT DO NOTHING`. If the key already exists, the result is returned as `duplicate=true` and no journal rows are written.
4. `journal_entries` is append-only — corrections use reversal entries, never updates.
5. `account_balances` is a derived snapshot updated atomically with **optimistic locking** (version column, up to 5 retries).

### DB schema (Flyway migrations in `src/main/resources/db/migration/`)

- **V1** — `accounts`, `journal_entries`, `account_balances`
- **V2** — `ledger_postings` (idempotency table, replaces the unique constraint on `journal_entries.idempotency_key`)

`journal_entries.idempotency_key` is still populated per row but idempotency is gated at the `ledger_postings` level so a single key covers the entire multi-leg entry group.

### Ledger conventions

- `DEBIT` increases an account's balance delta positively; `CREDIT` decreases it (snapshot delta = `DEBIT ? +amount : -amount`).
- Every posting must have at least one DEBIT and one CREDIT leg summing to the same amount — enforced by `BalancedPostingPolicy` before any persistence call.
- Account types in use: `SETTLED_CASH`, `UNSETTLED_CASH_SALES`, `UNSETTLED_CASH_BUYS`, `RESERVED_CASH`.
- All amounts are `NUMERIC(20,8)` in the DB and `BigDecimal` in Java. Never use `double` or `float` for money.
- Currency defaults to `AED`; multi-currency is out of scope for v1.

### Testing conventions

Integration tests use `@SpringBootTest` + `@Testcontainers` with `@DynamicPropertySource` to wire the container URL. Each test class manages its own `postgres:16-alpine` container. `@BeforeEach` wipes all ledger tables (accounts, journal_entries, ledger_postings, account_balances) in dependency order and inserts fresh fixture accounts.

Do not mock the database in integration tests — the posting engine's correctness depends on real transaction semantics and constraint enforcement.