<!-- generated -->
# axiz-core-inbox

Idempotent consumer: processed-message registry with JDBC store.

## Dependency

```xml
<dependency>
  <groupId>pe.axiz</groupId>
  <artifactId>axiz-core-inbox</artifactId>
</dependency>
```

The version is managed by `axiz-bom` (services inherit `axiz-parent-service`).

## Main types

| Type | Purpose |
|---|---|
| `InboxDeduplicator` | Connects the inbox to the event dispatcher: a message is processed once per consumer within the retention period. |
| `InboxException` | Inbox persistence failure. |
| `InboxStore` | Registry of consumed messages per consumer (idempotent consumer pattern). |
| `AsyncInboxStore` | CompletionStage port with the same owner fencing, leases and retention semantics. |

## Tests

Unit tests live in `src/test/java` and document the expected behavior; run them with `./mvnw -pl core/axiz-core-inbox verify`.

## JDBC extraction in 2.0

The JDBC implementation and SQL resources now live in `axiz-adapter-inbox-jdbc`; this module contains
framework-neutral contracts and orchestration. `TransactionRunner` remains in
`pe.axiz.core.commons.tx`. Use the capability catalog/`axiz upgrade` to add JDBC adapters with
`runtime` scope in a service. See [JDBC migration](../../docs/migration/2.0/JDBC.md).
