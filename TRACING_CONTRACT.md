# Tracing and Correlation Contract

This service uses OpenTelemetry (W3C Trace Context) plus an application-level correlation ID.

## Required headers

- `X-Correlation-Id` (required, UUID recommended)
- `traceparent` (W3C trace context)
- `tracestate` (optional W3C companion)

Recommended business metadata headers:

- `X-Event-Id`
- `X-Reference-Id`
- `X-Customer-Id`

## HTTP ingress/egress behavior

- On inbound HTTP:
  - If `X-Correlation-Id` is present, propagate as-is.
  - If missing, generate a new UUID.
- On outbound HTTP response:
  - Always include `X-Correlation-Id`.
  - Echo `traceparent`/`tracestate` if present on request.

## Logging (MDC)

During request processing, MDC must include:

- `correlationId`
- `traceId` (from OTel bridge)
- `spanId` (from OTel bridge)

Optional MDC keys for business observability (when available):

- `eventId`
- `referenceId`
- `customerId`
- `entryGroupId`

## Kafka propagation contract

For every produced message, producers must set:

- `X-Correlation-Id`
- `traceparent`
- `tracestate` (when present)
- `X-Event-Id` / `X-Reference-Id` / `X-Customer-Id` (when applicable)

Consumers must:

- Read and propagate these headers into downstream produced messages.
- Put `correlationId` and business keys into MDC while processing.
- Preserve `traceparent` to keep a single distributed trace across
  `Kafka -> ledger -> settlement -> reconciliation`.

