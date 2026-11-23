<!-- GENERADO por scripts/sync_reference.py desde axiz-framework: no editar -->
# axiz-sequence-spring-boot-autoconfigure

Secuencias atómicas con formato sobre JDBC, R2DBC nativo o DynamoDB, seleccionadas según la pila de la aplicación.

## Dependencia

```xml
<dependency>
  <groupId>pe.axiz</groupId>
  <artifactId>axiz-sequence-spring-boot-autoconfigure</artifactId>
</dependency>
```

La versión la gestiona `axiz-bom` (los servicios heredan `axiz-parent-service`).

## Tipos principales

| Tipo | Propósito |
|---|---|
| `AxizSequenceAutoConfiguration` | Secuencias de negocio atómicas sobre Aurora (tabla de contadores) o DynamoDB (contador atómico). |
| `AxizSequenceProperties` | Secuencias de negocio (`axiz.sequence.*`). |
| `Sequences` | Generadores de las secuencias configuradas; los nombres desconocidos fallan de inmediato. |
| `ReactiveSequences` | Fachada `Mono<String>` diferida sobre la asignación asíncrona nativa. |

Tipos relacionados: `AxizSequenceDynamoDbAutoConfiguration` están en `axiz-sequence-dynamodb-spring-boot-autoconfigure`; `AxizSequenceR2dbcAutoConfiguration` están en `axiz-sequence-r2dbc-spring-boot-autoconfigure`.

## Límites de pila y de transacción

Las aplicaciones servlet y no web publican `SequenceStore` y `Sequences`. Las aplicaciones reactivas
publican `ReactiveSequences`, con `DynamoDbAsyncClient`/`AsyncSequenceStore` o el `ReactiveSequenceStore`
del kit. No crean clientes síncronos ni beans de secuencias JDBC. El valor relacional por defecto `jdbc`
selecciona R2DBC nativo en una aplicación reactiva; allí también se acepta `axiz.sequence.store=r2dbc`.
La falta de backends nativos hace fallar el arranque.

Las reservas se confirman de forma independiente del rollback de negocio. Hikari y R2DBC usan un pool de
reservas separado y acotado, de modo que un pool de negocio totalmente ocupado no puede impedir el avance
del contador. El puerto R2DBC sigue siendo `Mono`; el puente privado de Spring hacia el asignador por
bloques del JDK, de forma intencional, no lleva una transacción de negocio a la reserva independiente.

```yaml
axiz:
  sequence:
    pool:
      max-size: 2
      acquire-timeout: 5s
    sequences:
      operation:
        prefix: OP-
        width: 8
        block-size: 10
```

`sequences.next("operation")` devuelve `String` para `Sequences` y `Mono<String>` para
`ReactiveSequences`. Cada suscripción reactiva asigna un valor. Los bloques pueden dejar huecos.
Las tablas relacionales se aprovisionan con la migración del servicio; los servicios generados incluyen
la tabla de secuencias al seleccionar esta capacidad.

Para implementaciones JDBC con pool personalizadas se aporta un `axizSequenceDataSource` dedicado; para
fábricas R2DBC personalizadas, `axizSequenceConnectionFactory`. El recurso principal se marca con
`@Primary` al declarar varios beans. El framework cierra solo los recursos de reserva que crea; los
recursos provistos conservan su propio ciclo de vida. Los permisos y el enrutamiento de la base de datos
deben permitir las escrituras del contador.

## Pruebas

Las pruebas unitarias están en `src/test/java` y documentan el comportamiento esperado; se ejecutan con `./mvnw -pl spring/axiz-sequence-spring-boot-autoconfigure verify`.
