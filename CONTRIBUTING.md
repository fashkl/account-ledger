# Contributing Guide

## Phase 0 Conventions
- Keep package-by-feature from the start (`platform`, `shared`, then domain modules in later phases).
- Keep ledger writes transactional and append-only.
- Use UTC for all persisted timestamps.
- Enforce idempotency on all write paths.

## Error Model
- API errors must return a consistent JSON shape via `ApiError`.
- Validation failures return HTTP 400 with field-level violations.
- Unexpected failures return HTTP 500 with a generic message (no internals leaked).

## Testing
- Prefer unit tests for accounting rules.
- Use Testcontainers for database-backed integration tests.
- Every schema migration should be covered by at least one integration test assertion.

## Logging and Observability
- Use structured, correlation-friendly logs.
- Expose health/info/prometheus actuator endpoints.
- Keep tracing enabled in non-prod-like environments for debugging.
