<!-- generated -->
# axiz-adapter-outbox-r2dbc

Outbox transaccional en Aurora PostgreSQL sobre R2DBC: escritor no bloqueante y almacén del relay.

## Dependencia

```xml
<dependency>
  <groupId>pe.axiz</groupId>
  <artifactId>axiz-adapter-outbox-r2dbc</artifactId>
</dependency>
```

La versión la gestiona `axiz-bom` (los servicios heredan `axiz-parent-service`).

## Tipos principales

| Tipo | Propósito |
|---|---|
| `R2dbcOutboxStore` | Almacén de outbox en Aurora PostgreSQL sobre R2DBC, con la misma tabla que el almacén JDBC (`axiz/sql/outbox-postgresql.sql`). |

Tipos relacionados: `ReactiveOutboxWriter` están en `axiz-kit-outbox-reactor`.

## Pruebas

Las pruebas unitarias están en `src/test/java` y documentan el comportamiento esperado; se ejecutan con `./mvnw -pl adapters/axiz-adapter-outbox-r2dbc verify`.

## Límites operativos de PostgreSQL y pruebas de fallos

Las esperas de conexión, sentencia y lock se acotan según la carga del relay. Conviene
`idle_in_transaction_session_timeout` para transacciones abandonadas y, desde PostgreSQL 17,
`transaction_timeout` para su duración total. Los valores de rol o de sesión deben ser compatibles
con todas las transacciones que usan ese rol, incluido el trabajo de negocio; Axiz no cambia la
configuración global del servidor. Ver los
[valores por defecto del cliente de PostgreSQL 17](https://www.postgresql.org/docs/17/runtime-config-client.html).
Los timeouts del servidor complementan la cancelación y la limpieza del cliente; no las reemplazan.

El fixture de integración con PostgreSQL limita las esperas de conexión, lectura, consulta y lock,
acota de forma explícita la creación del esquema y da al fork de failsafe un plazo final de proceso.
Se usa la opción JVM soportada de Netty `io.netty.allocator.type=pooled` **solo en los forks de prueba
de este módulo**, para evitar la contención del allocator adaptativo bajo carga. BlockHound sigue
instalado, con una prueba negativa que rechaza el bloqueo en la aplicación; no se agrega ninguna
excepción a nivel de aplicación o de paquete. La configuración del allocator del driver en
producción no cambia.

La inyección de fallos usa el `CodecRegistrar` público del driver: una fila real del outbox
bloqueada provoca una excepción o un Error de decodificación en el cliente mientras PostgreSQL sigue
activo. Una conexión JDBC independiente comprueba el PID del backend capturado y sus locks en menos
de tres segundos, antes de la red de seguridad de 30 segundos del fixture para transacciones
inactivas. Esta prueba cubre esas rutas de decodificación; no establece que cada error del pipeline
del driver tenga la misma limpieza ni reproduce el comportamiento exacto del allocator adaptativo.

Una transacción fallida revierte los cambios en la base. `SKIP LOCKED` permite que otros relays
pasen de largo las filas bloqueadas; no evita envíos duplicados. La entrega sigue siendo al menos
una vez y los consumidores deben ser idempotentes.
