<!-- generated -->
# axiz-kit-r2dbc

Non-blocking Aurora PostgreSQL access over R2DBC: named-parameter SQL, transactions, IAM authentication tokens.

## Dependency

```xml
<dependency>
  <groupId>pe.axiz</groupId>
  <artifactId>axiz-kit-r2dbc</artifactId>
</dependency>
```

The version is managed by `axiz-bom` (services inherit `axiz-parent-service`).

## Main types

| Type | Purpose |
|---|---|
| `DatabasePasswordProvider` | Supplies the password of each new database connection; lets the pool use short-lived credentials (IAM authentication tokens) without restarting. |
| `EncryptedText` | A text column stored encrypted (AES-256-GCM with the field encryption keys of the service). |
| `NamedSql` | SQL with `:name` placeholders translated to PostgreSQL positional ones (`$1`). |
| `R2dbcRow` | `SqlRow` over an R2DBC row. |
| `R2dbcSql` | `ReactiveSql` directly over an R2DBC `ConnectionFactory` (normally the pool). |
| `R2dbcTransactionRunner` | Transactions for blocking callers over R2DBC (the outbox relay and message consumers, which run on their own threads, never on an event loop): the connection is bound to the thread while the work runs, so `R2dbcSql` calls made by the work join the transaction. |
| `R2dbcUrls` | R2DBC URL helpers (Flyway still migrates over JDBC; IAM tokens need host and port). |
| `RdsIamPasswordProvider` | IAM authentication tokens for Aurora PostgreSQL. |
| `ReactiveSql` | Non-blocking SQL with named parameters (`:name`). |
| `ReactiveSequenceStore` | Native counter port returning a committed block endpoint. |
| `R2dbcSequenceStore` | Atomic PostgreSQL upsert and independent transaction for sequence reservations. |
| `RowMapper` | Maps a row to an object. |
| `SqlParams` | Named parameters of a statement with their types, so null values can be bound (R2DBC needs the type of a null). |
| `SqlRow` | A result row, independent of the driver. |

Sequence reservations deliberately use an independent connection and transaction: a business
rollback must not make a cached block available to another replica. Business persistence and
outbox continue to use the caller's Reactor transaction context through `ReactiveSql`.

## Tests

Unit tests live in `src/test/java` and document the expected behavior; run them with `./mvnw -pl kits/axiz-kit-r2dbc verify`.
