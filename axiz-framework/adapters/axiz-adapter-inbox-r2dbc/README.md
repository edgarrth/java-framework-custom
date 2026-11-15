<!-- generated -->
# axiz-adapter-inbox-r2dbc

Inbox (idempotent consumer) on Aurora PostgreSQL over R2DBC.

## Dependency

```xml
<dependency>
  <groupId>pe.axiz</groupId>
  <artifactId>axiz-adapter-inbox-r2dbc</artifactId>
</dependency>
```

The version is managed by `axiz-bom` (services inherit `axiz-parent-service`).

## Main types

| Type | Purpose |
|---|---|
| `R2dbcInboxStore` | Inbox on Aurora PostgreSQL over R2DBC, same table as the JDBC store (`axiz/sql/inbox-postgresql.sql`). |
| `ReactiveInboxStore` | Native Reactor contract preserving subscription and transaction context. |
| `R2dbcReactiveInboxStore` | Deferred statements with owner fencing, bounded purge counts and cancellable I/O. |

Application flows use `ReactiveInboxStore`. `R2dbcInboxStore` shares that implementation and
waits with a deadline only at the dedicated poll/purge worker boundary for poll and purge workers.
No synchronous statement runs on an event loop. Both variants use the existing inbox schema.

## Tests

Unit tests live in `src/test/java` and document the expected behavior; run them with `./mvnw -pl adapters/axiz-adapter-inbox-r2dbc verify`.
