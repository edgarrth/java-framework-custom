<!-- generated -->
# axiz-core-outbox

Transactional outbox: JDBC store and relay to any EventPublisher, at-least-once.

## Dependency

```xml
<dependency>
  <groupId>pe.axiz</groupId>
  <artifactId>axiz-core-outbox</artifactId>
</dependency>
```

The version is managed by `axiz-bom` (services inherit `axiz-parent-service`).

## Main types

| Type | Purpose |
|---|---|
| `OutboxException` | Outbox persistence failure. |
| `OutboxRecord` | A pending (or processed) outbox entry. |
| `OutboxRelay` | Moves outbox entries to the broker (at-least-once). |
| `OutboxStore` | Persistence port of the outbox. |
| `OutboxWriteTracing` | Keeps the trace of the caller with each event written to the outbox. |
| `OutboxWriter` | Registers events in the outbox within the caller's transaction. |

## Tests

Unit tests live in `src/test/java` and document the expected behavior; run them with `./mvnw -pl core/axiz-core-outbox verify`.

## JDBC extraction in 2.0

The JDBC implementation and SQL resources now live in `axiz-adapter-outbox-jdbc`; this module contains
framework-neutral contracts and orchestration. `TransactionRunner` remains in
`pe.axiz.core.commons.tx`. Use the capability catalog/`axiz upgrade` to add JDBC adapters with
`runtime` scope in a service. See [JDBC migration](../../docs/migration/2.0/JDBC.md).
