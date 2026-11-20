<!-- generated -->
# axiz-kit-r2dbc

Non-blocking Aurora PostgreSQL access over R2DBC: named-parameter SQL, transactions and a neutral password-provider contract.

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
| `ReactiveSql` | Non-blocking SQL with named parameters (`:name`). |
| `RowMapper` | Maps a row to an object. |
| `SqlParams` | Named parameters of a statement with their types, so null values can be bound (R2DBC needs the type of a null). |
| `SqlRow` | A result row, independent of the driver. |

Sequence reservations live in `axiz-adapter-sequence-r2dbc` and use an independent connection and transaction: a business
rollback must not make a cached block available to another replica. Business persistence and
outbox continue to use the caller's Reactor transaction context through `ReactiveSql`.

## Tests

Unit tests live in `src/test/java` and document the expected behavior; run them with `./mvnw -pl kits/axiz-kit-r2dbc verify`.

AWS IAM signing is provided by `axiz-adapter-database-auth-aws`. This kit has no AWS SDK dependency.

`R2dbcSql.inTransaction` joins an existing connection for the same factory;
`inIndependentTransaction` explicitly suspends that binding for a separate committed reservation.
Sequence allocation uses the latter. Size the pool above the maximum simultaneous outer business
transactions that may reserve a block, or configure a dedicated sequence factory; a saturated
single-connection pool cannot service an independent reservation. Keep pool acquisition timeouts
finite. Cleanup failures do not rewrite confirmed work; a lost commit acknowledgement raises
`TransactionOutcomeUnknownException` and forbids automatic replay. Explicit server rollback
(serialization/deadlock or integrity rejection) preserves the driver's exception; SQLState 40003
still means unknown completion.

An uncertain transaction closes the physical connection before releasing a pool wrapper. If the
driver reports a close error but local validation confirms the connection is closed, the wrapper
is released so the pool can evict it. If the connection remains live or its state cannot be
confirmed, it is not returned for reuse: the R2DBC SPI has no generic pool-invalidation operation.
Monitor pool acquisition failures and configure finite acquisition timeouts; persistent driver
failures may require recreating the affected pool. This deliberately preserves transaction
isolation instead of returning a connection whose transaction outcome is unknown.

A failed physical close with a live or unverified connection emits the constant error code
`AXIZ_R2DBC_CONNECTION_QUARANTINED`. Alert on this signal and monitor pool acquisition capacity.
It deliberately logs no driver exception message or connection identifier. A closed physical
connection is evicted and its pool slot can be acquired again, including after a failed commit.
