# axiz-adapter-inbox-jdbc

Persistencia del inbox y recursos de esquema sobre JDBC.

La tecnología Java/JDBC pertenece aquí; los puertos de negocio permanecen en `axiz-core-inbox`.
Se usa mediante la capacidad Spring correspondiente o proporcionando un `ConnectionProvider` consciente de transacciones.

Se ejecuta `./mvnw -pl adapters/axiz-adapter-inbox-jdbc -am verify` desde la raíz del framework.
