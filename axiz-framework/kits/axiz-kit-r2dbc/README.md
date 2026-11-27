<!-- generated -->
# axiz-kit-r2dbc

Acceso no bloqueante a Aurora PostgreSQL sobre R2DBC: SQL con parámetros nombrados, transacciones y un contrato neutral de proveedor de contraseñas.

## Dependencia

```xml
<dependency>
  <groupId>pe.axiz</groupId>
  <artifactId>axiz-kit-r2dbc</artifactId>
</dependency>
```

La versión la gestiona `axiz-bom` (los servicios heredan `axiz-parent-service`).

## Tipos principales

| Tipo | Propósito |
|---|---|
| `DatabasePasswordProvider` | Entrega la contraseña de cada nueva conexión a la base de datos; permite que el pool use credenciales de vida corta (tokens de autenticación IAM) sin reiniciar. |
| `EncryptedText` | Columna de texto guardada cifrada (AES-256-GCM con las claves de cifrado de campos del servicio). |
| `NamedSql` | SQL con marcadores `:name` traducidos a los posicionales de PostgreSQL (`$1`). |
| `R2dbcRow` | `SqlRow` sobre una fila de R2DBC. |
| `R2dbcSql` | `ReactiveSql` directamente sobre un `ConnectionFactory` de R2DBC (normalmente el pool). |
| `R2dbcTransactionRunner` | Transacciones para llamadores bloqueantes sobre R2DBC (el relay del outbox y los consumidores de mensajes, que corren en sus propios hilos y nunca en un event loop): la conexión queda ligada al hilo mientras se ejecuta el trabajo, de modo que las llamadas a `R2dbcSql` que hace el trabajo se unen a la transacción. |
| `R2dbcUrls` | Utilidades de URL de R2DBC (Flyway migra sobre JDBC; los tokens IAM necesitan host y puerto). |
| `ReactiveSql` | SQL no bloqueante con parámetros nombrados (`:name`). |
| `RowMapper` | Mapea una fila a un objeto. |
| `SqlParams` | Parámetros nombrados de una sentencia con sus tipos, para poder ligar valores nulos (R2DBC necesita el tipo de un nulo). |
| `SqlRow` | Fila de resultado, independiente del driver. |

Las reservas de secuencias están en `axiz-adapter-sequence-r2dbc` y usan una conexión y una transacción
independientes: un rollback del negocio no debe dejar disponible para otra réplica un bloque en caché.
La persistencia de negocio y el outbox usan el contexto transaccional de Reactor del llamador mediante
`ReactiveSql`.

## Pruebas

Las pruebas unitarias están en `src/test/java` y documentan el comportamiento esperado; se ejecutan con `./mvnw -pl kits/axiz-kit-r2dbc verify`.

La firma IAM de AWS la aporta `axiz-adapter-database-auth-aws`. Este kit no depende del SDK de AWS.

`R2dbcSql.inTransaction` se une a una conexión existente de la misma fábrica;
`inIndependentTransaction` suspende de forma explícita ese binding para una reserva confirmada por
separado. La asignación de secuencias usa esta última. El pool se dimensiona por encima del máximo de
transacciones de negocio externas simultáneas que pueden reservar un bloque, o se configura una fábrica
dedicada para secuencias; un pool saturado de una sola conexión no puede atender una reserva
independiente. Los timeouts de adquisición del pool se mantienen finitos. Los fallos de limpieza no
reescriben el trabajo confirmado; un acuse de commit perdido lanza
`TransactionOutcomeUnknownException` e impide la repetición automática. Un rollback explícito del
servidor (serialización, deadlock o rechazo de integridad) conserva la excepción del driver; el
SQLState 40003 significa que la finalización es desconocida.

Una transacción incierta cierra la conexión física antes de liberar el envoltorio del pool. Si el
driver informa un error al cerrar pero la validación local confirma que la conexión está cerrada, se
libera el envoltorio para que el pool pueda desalojarla. Si la conexión sigue viva o no se puede
confirmar su estado, no se devuelve para reutilizarla: el SPI de R2DBC no tiene una operación genérica
para invalidar conexiones del pool. Se monitorean los fallos de adquisición del pool y se configuran
timeouts de adquisición finitos; los fallos persistentes del driver pueden requerir recrear el pool
afectado. Así se preserva a propósito el aislamiento transaccional en lugar de devolver una conexión
cuyo resultado transaccional se desconoce.

Un cierre físico fallido con una conexión viva o no verificada emite el código de error constante
`AXIZ_R2DBC_CONNECTION_QUARANTINED`. Se alerta sobre esta señal y se monitorea la capacidad de
adquisición del pool. A propósito no se registra el mensaje de excepción del driver ni el
identificador de la conexión. Una conexión física cerrada se desaloja y su espacio en el pool puede
adquirirse de nuevo, incluso después de un commit fallido.
