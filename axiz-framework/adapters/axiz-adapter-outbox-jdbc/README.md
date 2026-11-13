# axiz-adapter-outbox-jdbc

Outbox persistence and schema resources over JDBC.

Java/JDBC technology belongs here; business ports remain in `axiz-core-outbox`.
Use through the matching Spring capability, or provide a transaction-aware `ConnectionProvider`
and a `TransactionRunner` bound to the same data source. Plain JDBC uses `JdbcTransactions`
for both ports; Spring uses `SpringConnectionProvider` with `SpringTransactionRunner`.
Each operation joins the business transaction or opens a short local transaction. The store
never manages commit, rollback or pool recovery itself.

Run `./mvnw -pl adapters/axiz-adapter-outbox-jdbc -am verify` from the framework root.
