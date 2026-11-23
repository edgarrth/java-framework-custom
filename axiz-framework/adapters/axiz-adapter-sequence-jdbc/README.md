# axiz-adapter-sequence-jdbc

Persistencia de secuencias y recursos de esquema sobre JDBC.

La tecnología Java/JDBC pertenece aquí; los puertos de negocio permanecen en `axiz-core-sequence`.
Se usa mediante la capacidad Spring correspondiente o proporcionando un `ConnectionProvider` consciente de transacciones.

Se ejecuta `./mvnw -pl adapters/axiz-adapter-sequence-jdbc -am verify` desde la raíz del framework.
