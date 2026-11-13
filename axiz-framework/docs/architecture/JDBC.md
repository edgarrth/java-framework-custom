# JDBC boundaries in v2

Core keeps business ports and orchestration without java.sql or javax.sql dependencies.
`axiz-kit-jdbc` owns ConnectionProvider, JdbcTransactions and SqlStates. JDBC outbox, inbox,
saga and sequence implementations live in their corresponding adapters. Services declare adapters
at runtime and own versioned Flyway schema migrations. The BOM manages every current module.

Core transactions use `pe.axiz.core.commons.tx.TransactionRunner`. Outbox claims and completion
updates always carry an owner. No unfenced compatibility methods or old-coordinate mappings are
retained. Schema preflight remains diagnostic and read-only; it does not silently skip evidence.
