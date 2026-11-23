# axiz-kit-jdbc

Conexiones JDBC conscientes de transacciones, utilidades de SQLSTATE y transacciones locales.

La tecnología Java/JDBC pertenece aquí; los puertos de negocio permanecen en `axiz-core-commons`.
Se usa mediante la capacidad Spring correspondiente o proporcionando un `ConnectionProvider` consciente de transacciones.
`AutoCommitConnectionProvider` entrega a los almacenes cuyas escrituras deben ser visibles de inmediato
(leases, estado de procesos) su propia conexión en auto-commit, independiente de cualquier transacción del llamador.

Se ejecuta `./mvnw -pl kits/axiz-kit-jdbc -am verify` desde la raíz del framework.
