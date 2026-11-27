# axiz-adapter-outbox-jdbc

Persistencia del outbox y recursos de esquema sobre JDBC.

La tecnología Java/JDBC pertenece aquí; los puertos de negocio permanecen en `axiz-core-outbox`.
Se usa mediante la capacidad Spring correspondiente o proporcionando un `ConnectionProvider` consciente de transacciones
y un `TransactionRunner` ligado a la misma fuente de datos. JDBC sin Spring usa `JdbcTransactions`
para ambos puertos; Spring usa `SpringConnectionProvider` con `SpringTransactionRunner`. Cada
operación se une a la transacción de negocio o abre una transacción local corta. El almacén nunca
gestiona por sí mismo el commit, el rollback ni la recuperación del pool.

Se ejecuta `./mvnw -pl adapters/axiz-adapter-outbox-jdbc -am verify` desde la raíz del framework.
