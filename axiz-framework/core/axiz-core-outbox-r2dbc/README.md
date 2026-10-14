<!-- generated -->
# axiz-core-outbox-r2dbc

Transactional outbox on Aurora PostgreSQL over R2DBC: non-blocking writer and relay store.

## Dependency

```xml
<dependency>
  <groupId>pe.axiz</groupId>
  <artifactId>axiz-core-outbox-r2dbc</artifactId>
</dependency>
```

The version is managed by `axiz-bom` (services inherit `axiz-parent-service`).

## Main types

| Type | Purpose |
|---|---|
| `R2dbcOutboxStore` | Outbox store on Aurora PostgreSQL over R2DBC, same table as the JDBC store (`axiz/sql/outbox-postgresql.sql`). |
| `ReactiveOutboxWriter` | Registers events in the outbox inside the caller's reactive transaction: they are published if and only if that transaction commits. |

## Tests

Unit tests live in `src/test/java` and document the expected behavior; run them with `./mvnw -pl core/axiz-core-outbox-r2dbc verify`.
