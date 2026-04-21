# Phase 0 Status

## Completed
- Spring Boot service skeleton initialized with Gradle wrapper.
- PostgreSQL + Flyway runtime configured.
- Docker Compose added for local Postgres.
- Baseline ledger schema migration added.
- API error envelope + global exception handler scaffolded.
- Platform ping endpoint added.
- Testcontainers integration test added to verify schema bootstrap.
- Actuator/tracing scaffolding configured.

## Remaining for your manual review
- Review package naming and API conventions.
- Confirm baseline schema should stay in Phase 0 or move fully to Phase 1.
- Confirm observability defaults (sampling=1.0) for local/dev.
