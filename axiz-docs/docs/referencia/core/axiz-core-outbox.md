<!-- GENERADO por scripts/sync_reference.py desde axiz-framework: no editar -->
# axiz-core-outbox

El relay usa un plazo monotónico tomado antes de obtener su conexión de reclamo. Inicia un envío
solo si el lease restante cubre el presupuesto declarado por el publicador. Los adaptadores de base
de datos usan la hora de la base para reclamar y vencer leases; `OutboxRecord.leaseUntil` es
informativo para el relay. El `Clock` de la aplicación sigue fechando la publicación y la
programación de reintentos.

`BoundedPublisher` y `PublishBound` propagan los presupuestos de rutas conocidas y desconocidas a
través del enrutamiento y la observación. Un publicador propio sin límite conocido necesita un
`axiz.outbox.relay.publish-budget` explícito al crear el relay. El valor de respaldo cubre las rutas
desconocidas y nunca reduce un máximo conocido. Quien consume core directamente puede usar el
constructor que recibe de forma explícita el lease y el presupuesto de publicación. Los reclamos y
las actualizaciones de finalización requieren un propietario y quedan protegidos por él.

`claim-allowance` (2 s por defecto) dimensiona el lease al arrancar; un reclamo más lento aún puede
publicar si su presupuesto restante real alcanza. La validación del lease exige
`lease - margin >= budget + claim-allowance`, con `margin = lease / 100 + 2 ms`; la igualdad se
acepta. Los callbacks del listener exponen la duración del reclamo y los presupuestos agotados sin
que core dependa de Micrometer.

Son garantías de admisión, no garantías estrictas de entrega en tiempo real. Las pausas del proceso
o una operación del broker que sigue después de que el llamador vence pueden exceder el lease. La
exclusión por propietario y los consumidores idempotentes siguen siendo necesarios ante entregas
duplicadas y resultados inciertos.

Outbox transaccional: almacén JDBC y relay hacia cualquier EventPublisher, al menos una vez.

## Dependencia

```xml
<dependency>
  <groupId>pe.axiz</groupId>
  <artifactId>axiz-core-outbox</artifactId>
</dependency>
```

La versión la gestiona `axiz-bom` (los servicios heredan `axiz-parent-service`).

## Tipos principales

| Tipo | Propósito |
|---|---|
| `OutboxException` | Fallo de persistencia del outbox. |
| `OutboxRecord` | Una entrada del outbox pendiente (o procesada). |
| `OutboxRelay` | Mueve las entradas del outbox al broker (al menos una vez). |
| `OutboxStore` | Puerto de persistencia del outbox. |
| `OutboxWriteTracing` | Conserva la traza del llamador en cada evento escrito en el outbox. |
| `OutboxWriter` | Registra eventos en el outbox dentro de la transacción del llamador. |

## Pruebas

Las pruebas unitarias están en `src/test/java` y documentan el comportamiento esperado; se ejecutan con `./mvnw -pl core/axiz-core-outbox verify`.

## JDBC

La implementación JDBC y los recursos SQL están en `axiz-adapter-outbox-jdbc`; este módulo contiene contratos y
orquestación neutrales respecto del framework, y `TransactionRunner` está en `pe.axiz.core.commons.tx`. Un servicio agrega el
adaptador JDBC de una capacidad con `axiz add` (o con el Initializr); ver
[límites JDBC](../../arquitectura/jdbc.md).
