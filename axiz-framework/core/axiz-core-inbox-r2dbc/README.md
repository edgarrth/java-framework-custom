<!-- generated -->
# axiz-core-inbox-r2dbc

Inbox (idempotent consumer) on Aurora PostgreSQL over R2DBC.

## Dependency

```xml
<dependency>
  <groupId>pe.axiz</groupId>
  <artifactId>axiz-core-inbox-r2dbc</artifactId>
</dependency>
```

The version is managed by `axiz-bom` (services inherit `axiz-parent-service`).

## Main types

| Type | Purpose |
|---|---|
| `R2dbcInboxStore` | Inbox on Aurora PostgreSQL over R2DBC, same table as the JDBC store (`axiz/sql/inbox-postgresql.sql`). |

## Tests

Unit tests live in `src/test/java` and document the expected behavior; run them with `./mvnw -pl core/axiz-core-inbox-r2dbc verify`.
