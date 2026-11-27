# axiz-adapter-saga-jdbc

Persistencia de sagas y recursos de esquema sobre JDBC.

La tecnología Java/JDBC pertenece aquí; los puertos de negocio permanecen en `axiz-core-saga`.
Se usa mediante la capacidad Spring correspondiente o proporcionando un `ConnectionProvider` consciente de transacciones.

Se ejecuta `./mvnw -pl adapters/axiz-adapter-saga-jdbc -am verify` desde la raíz del framework.

## Esquema y límite transaccional

La tabla `axiz_saga` incluye `execution_owner VARCHAR(36)` y
`execution_until TIMESTAMP WITH TIME ZONE` para los leases de ejecución (esquemas de arranque en
`axiz/sql/saga-*.sql`).

Las escrituras de estado y de lease deben confirmarse antes de invocar a un participante. La capacidad
Spring entrega al almacén un `AutoCommitConnectionProvider`: cada operación toma su propia conexión en
auto-commit, de modo que el orquestador funciona igual dentro de un `@UseCase` o de un manejador de
eventos transaccional, y un rollback del llamador nunca borra el estado de la saga. Se rechaza un
`ConnectionProvider` personalizado ligado a la transacción del llamador. Las transacciones de los
participantes permanecen locales a sus servicios. `CURRENT_TIMESTAMP` se lee desde la base de datos
para adquirir y renovar el lease; los predicados de propietario vigente y de versión protegen las
actualizaciones de estado.
