# Account Ledger Service

Production-oriented ledger service for posting engine, order lifecycle, cash movement (withdrawals), settlement, reconciliation, Kafka ingestion, and operational hardening.

## What This Service Does

- Maintains an immutable double-entry ledger.
- Applies account-balance snapshots with domain and DB guards.
- Manages order lifecycle states and buying power.
- Handles deposit/withdrawal cash events with idempotency.
- Runs scheduled settlement and reconciliation jobs.
- Supports Kafka-driven ingestion with DLQ and consumer controls.
- Provides seed endpoints for deterministic local/manual testing.

## Tech Stack

- Java 21
- Spring Boot 4.0.5
- JDBC + Flyway (no JPA runtime)
- PostgreSQL 16
- Kafka
- Resilience4j
- OpenTelemetry + Micrometer
- JSON structured logs (Logstash encoder)
- Swagger/OpenAPI via Springdoc

## Architecture

This codebase follows hexagonal/clean architecture with DDD-style boundaries:

- `src/main/java/com/mohamedali/ledger/ledger/domain`:
  domain models, policies, and domain exceptions
- `src/main/java/com/mohamedali/ledger/ledger/application`:
  use-cases and ports
- `src/main/java/com/mohamedali/ledger/ledger/adapter`:
  web + persistence adapters
- `src/main/java/com/mohamedali/ledger/platform`:
  jobs, schedulers, Kafka, ops endpoints, seeding
- `src/main/java/com/mohamedali/ledger/shared`:
  tracing, global error handling, infra utilities

Core ledger convention used across the system:

- `DEBIT = +delta`
- `CREDIT = -delta`

## Quick Start (Local)

### Option A — Full stack via Docker Compose (recommended)

Starts PostgreSQL, Kafka, and the application in one command:

```bash
docker compose up --build
```

| Service | Address |
|---|---|
| App | http://localhost:8080 |
| Kafka (external) | localhost:29092 |
| PostgreSQL | localhost:5432 |

### Option B — Run application manually

#### 1. Start backing services

```bash
docker compose up -d postgres kafka
```

#### 2. Export environment variables

```bash
export DB_URL=jdbc:postgresql://localhost:5432/account_ledger
export DB_USER=postgres
export DB_PASSWORD=superpass
export KAFKA_BOOTSTRAP_SERVERS=localhost:29092
export SPRING_PROFILES_ACTIVE=local
```

Optional (for admin seed endpoints):

```bash
export LEDGER_SEED_ENABLED=true
```

#### 3. Run application

```bash
./gradlew bootRun
```

#### 4. Verify service health

```bash
curl http://localhost:8080/api/v1/platform/ping
curl http://localhost:8080/actuator/health
```

## API Documentation (Swagger)

- Swagger UI: [http://localhost:8080/swagger-ui.html](http://localhost:8080/swagger-ui.html)
- OpenAPI JSON: [http://localhost:8080/v3/api-docs](http://localhost:8080/v3/api-docs)

## Main API Surface

### Ledger

- `POST /api/v1/ledger/postings`
- `GET /api/v1/ledger/accounts/{accountId}/balance`

### Orders

- `POST /api/v1/orders/events`
- `GET /api/v1/orders/buying-power/{customerId}?currency=AED`

### Cash / Withdrawals

- `POST /api/v1/cash/events`
- `GET /api/v1/cash/withdrawals/{withdrawalId}`
- `GET /api/v1/cash/accounts/{accountId}/balance`

### Jobs Admin

- `POST /api/v1/admin/jobs/settlement/run?settlementDate=YYYY-MM-DD&batchId=<id>`
- `POST /api/v1/admin/jobs/reconciliation/run`
- `POST /api/v1/admin/jobs/kafka/pause?reason=<text>`
- `POST /api/v1/admin/jobs/kafka/resume?reason=<text>`

### Seed Admin

- `POST /api/v1/admin/seed/reset`
- `POST /api/v1/admin/seed/run?dataset=medium&reset=true`
- `POST /api/v1/admin/seed/scenario?name=happy-path&reset=false`
- `GET /api/v1/admin/seed/catalog`

## Build and Test

### Full build and tests

```bash
./gradlew clean test
```

### Unit/integration test suite (default `test` excludes heavy tags)

```bash
./gradlew test
```

### Load/chaos tagged tests

```bash
./gradlew loadTest
./gradlew chaosTest
```

## Load Testing (k6)

Scripts are under `docs/k6/`.

### Posting load (staged ramp)

Ramp profile:
- 100 RPS for 1 minute
- 250 RPS for 1 minute
- 500 RPS for 2 minutes

```bash
k6 run docs/k6/posting-load.js -e BASE_URL=http://localhost:8080
```

### Balance-read load (staged ramp)

Ramp profile:
- 100 RPS for 1 minute
- 250 RPS for 1 minute
- 500 RPS for 2 minutes

```bash
k6 run docs/k6/balance-read-load.js -e BASE_URL=http://localhost:8080 -e CUSTOMER_ID=<customer-uuid>
```

### Reconciliation trigger load (staged ramp, per minute rate)

Ramp profile:
- 1 req/min for 5 minutes
- 2 req/min for 10 minutes
- 4 req/min for 15 minutes

```bash
k6 run docs/k6/reconciliation-load.js -e BASE_URL=http://localhost:8080
```

See detailed checklist in [docs/runbooks/load-and-chaos-validation.md](docs/runbooks/load-and-chaos-validation.md).

## Seeding Mock Data

Seeding is intentionally blocked unless:
- profile is `local`, or
- `LEDGER_SEED_ENABLED=true`

Run a deterministic medium dataset:

```bash
curl -X POST "http://localhost:8080/api/v1/admin/seed/run?dataset=medium&reset=true"
```

See runbook:
- [docs/runbooks/mock-data-seeding.md](docs/runbooks/mock-data-seeding.md)

## Configuration Reference

Primary settings are in:
- [application.yml](src/main/resources/application.yml)

Notable groups:
- `spring.datasource.*` primary DB
- `ledger.datasource.settlement.*` dedicated settlement connection pool
- `ledger.datasource.read-replica.*` optional read replica routing (see below)
- `spring.kafka.*` consumer/producer/ack mode
- `ledger.kafka.topic.*` main + DLQ topics
- `ledger.jobs.*` settlement and reconciliation schedules
- `resilience4j.*` circuit-breaker + retry behavior
- `springdoc.*` Swagger paths

### Read Replica

Balance and buying-power queries can be routed to a read replica to offload the primary under write load. Disabled by default (falls back to primary).

```yaml
ledger.datasource.read-replica:
  enabled: true
  url: jdbc:postgresql://<replica-host>:5432/account_ledger
  username: <user>
  password: <password>
```

A scheduled poller queries `pg_last_xact_replay_timestamp()` every 10 seconds. If replica lag exceeds the configured threshold the routing flag flips back to primary automatically.

## Tracing, Logging, and Observability

- Logs are structured JSON and include domain keys when available:
  `correlationId`, `eventId`, `referenceId`, `entryGroupId`, `customerId`, `traceId`, `spanId`
- Actuator metrics endpoint:
  `GET /actuator/prometheus`
- Tracing and propagation contract:
  [TRACING_CONTRACT.md](TRACING_CONTRACT.md)

## Event Compatibility and Contracts

- Backward compatibility policy:
  [EVENT_COMPATIBILITY_POLICY.md](EVENT_COMPATIBILITY_POLICY.md)

## Runbooks

- [Reconciliation Mismatch](docs/runbooks/reconciliation-mismatch.md)
- [Journal Invariant Incident](docs/runbooks/journal-invariant-incident.md)
- [Settlement Batch Recovery](docs/runbooks/settlement-batch-recovery.md)
- [Consumer Lag Escalation](docs/runbooks/consumer-lag-escalation.md)
- [Circuit Breaker Open](docs/runbooks/circuit-breaker-open.md)
- [Load and Chaos Validation](docs/runbooks/load-and-chaos-validation.md)

## Notes

- Flyway migrations are under `src/main/resources/db/migration`.
- This project expects UTC semantics for scheduled jobs and reconciliation windows.
- For production rollout, keep seed endpoints disabled.
