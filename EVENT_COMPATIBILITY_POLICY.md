# Event Contract Compatibility Policy

This policy defines how event schemas evolve across ledger-related producers and consumers.

## Versioning model

- Every event has:
  - `eventType` (business event name)
  - `schemaVersion` (integer, starting at `1`)
  - `eventId` (stable idempotency key input)
- Topic/stream names remain stable unless a hard break requires a new stream.

## Non-breaking changes (allowed in-place)

- Add optional fields.
- Add new enum values that existing consumers can safely ignore.
- Add metadata headers (for example tracing/correlation headers).
- Relax validation rules (broader accepted input) when old payloads remain valid.

For non-breaking changes:

- Keep the same `schemaVersion`.
- Preserve existing field meanings and defaults.
- Do not remove or rename existing fields.

## Breaking changes (require version bump)

- Remove a field.
- Rename a field.
- Change field type or format (for example `string` -> `number`).
- Change required/optional semantics in a way that can reject previously valid events.
- Change business meaning of a field.

For breaking changes:

- Increment `schemaVersion`.
- Producers must support dual-publish during migration (`N` and `N+1`) when needed.
- Consumers must support at least one prior version during rollout window.

## Compatibility guarantees

- Consumers must ignore unknown fields.
- Producers must not emit payloads that violate older consumer assumptions unless using a new `schemaVersion`.
- Idempotency key derivation must remain stable across compatible versions.

## Rollout rules

- Introduce new consumer support first, then producer switch.
- Monitor invalid-event and deserialization error metrics before removing prior version support.
- Document cutover date and deprecation window for each breaking change.

