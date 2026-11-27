<!-- GENERADO por scripts/sync_reference.py desde axiz-framework: no editar -->
# axiz-outbox-spring-boot-autoconfigure

El arranque del relay valida el presupuesto real del `EventPublisher`, incluidos los wrappers de
enrutamiento y observación, frente al lease. Para publicadores desconocidos o personalizados se configura
`axiz.outbox.relay.publish-budget`; no puede reducir el límite de una ruta conocida.
`axiz.outbox.relay.claim-allowance` vale 2 s por defecto y solo sirve para dimensionar al arranque; no es
un límite estricto de la latencia del claim. Los escritores del outbox sin relay local no requieren un
presupuesto de publicación; las comprobaciones del esquema siguen activas.

Las métricas opcionales del relay son `axiz.outbox.relay.claim.duration` y
`axiz.outbox.relay.budget.exhausted`. Un claim lento por sí solo no incrementa el contador de agotamiento.

Outbox transaccional sobre Aurora PostgreSQL (JDBC o R2DBC) con relay en segundo plano (autoconfiguración).

## Dependencia

```xml
<dependency>
  <groupId>pe.axiz</groupId>
  <artifactId>axiz-outbox-spring-boot-autoconfigure</artifactId>
</dependency>
```

La versión la gestiona `axiz-bom` (los servicios heredan `axiz-parent-service`).

## Tipos principales

| Tipo | Propósito |
|---|---|
| `AxizOutboxAutoConfiguration` | Outbox transaccional: eventos escritos en la transacción de negocio y reenviados al menos una vez a MSK, SNS o SQS mediante el publicador del starter de mensajería. |
| `AxizOutboxProperties` | Outbox transaccional (`axiz.outbox.*`) sobre Aurora PostgreSQL, con JDBC o R2DBC. |
| `CachedPendingCount` | Conteo de registros pendientes para el gauge `axiz.outbox.pending` cuando el almacén bloquea sobre un driver reactivo: un scrape nunca ejecuta la consulta en su propio hilo (que puede ser un event loop); lee el último valor y, cuando es más antiguo que `maxAge`, inicia una actualización en un hilo virtual. |
| `OutboxRelayRunner` | Relay en segundo plano sobre un hilo virtual: consulta, publica en lotes mientras hay trabajo, espera en caso contrario y purga periódicamente los registros publicados. |
| `ReactiveTransactionalEvents` | Equivalente reactivo de `TransactionalEvents` para servicios R2DBC: el evento se inserta en el outbox con la misma conexión y transacción que el cambio de negocio (`TransactionalOperator` o `@Transactional` en un método que devuelve `Mono`/`Flux`); el relay lo publica después del commit. |
| `TransactionalEvents` | Registra eventos en el outbox dentro de la transacción del llamador; el relay los publica después del commit. |

Tipos relacionados: `AxizOutboxR2dbcAutoConfiguration` están en `axiz-outbox-r2dbc-spring-boot-autoconfigure`.

## Pruebas

Las pruebas unitarias están en `src/test/java` y documentan el comportamiento esperado; se ejecutan con `./mvnw -pl spring/axiz-outbox-spring-boot-autoconfigure verify`.

## Protección técnica del payload

`axiz.outbox.payload-protection=envelope` activa el cifrado autenticado del payload;
`none` es el valor por defecto. El modo envelope requiere el `EnvelopeEncryptor` síncrono o nativo
correspondiente y hace fallar el arranque cuando falta. Los registros protegidos no se pueden
leer en modo `none`; la migración de datos y las claves de lectura retenidas se planifican antes
de cambiar el modo. Se recomienda revisar la [protección de almacenes técnicos](../../guias/proteccion-de-almacenes-tecnicos.md).

`axiz.outbox.relay.shutdown-grace` vale 15 segundos por defecto (se permiten de 0 a 5 minutos). La
detención rechaza un lote nuevo, permite que el lote actual termine dentro de ese presupuesto y luego
interrumpe el trabajo pendiente. El callback de detención se ejecuta solo cuando todos los workers y
recursos terminaron realmente. Una limpieza fallida o una detención sin terminar rechaza el reinicio;
nunca crea en silencio otro grupo de workers. La entrega sigue siendo al menos una vez, incluidos los
envíos inciertos interrumpidos después del periodo de gracia.
