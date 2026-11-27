<!-- generated -->
# axiz-saga-spring-boot-autoconfigure

Orquestador de sagas con estado persistido en PostgreSQL y recuperación de sagas detenidas (autoconfiguración).

## Dependencia

```xml
<dependency>
  <groupId>pe.axiz</groupId>
  <artifactId>axiz-saga-spring-boot-autoconfigure</artifactId>
</dependency>
```

La versión la gestiona `axiz-bom` (los servicios heredan `axiz-parent-service`).

## Tipos principales

| Tipo | Propósito |
|---|---|
| `AxizSagaAutoConfiguration` | Orquestador de sagas con estado en Aurora, reintentos de fallos transitorios y compensaciones. |
| `AxizSagaProperties` | Sagas orquestadas (`axiz.saga.*`) con estado persistido y compensación. |
| `JacksonContextCodec` | Contextos de saga en JSON con el `JsonMapper` del servicio: los contextos deben ser records con valores simples (ids, montos, flags), nunca secretos, porque se persisten con el estado de la saga. |
| `MeteredSagaListener` | Métricas de sagas: pasos completados, compensaciones, pasos en duda (alerta sobre `axiz.saga.in_doubt`) y sagas terminadas por estado. |

## Pruebas

Las pruebas unitarias están en `src/test/java` y documentan el comportamiento esperado; se ejecutan con `./mvnw -pl spring/axiz-saga-spring-boot-autoconfigure verify`.

## Protección técnica del payload

`axiz.saga.context-protection=envelope` activa el cifrado autenticado del payload;
`none` es el valor por defecto. El modo envelope requiere el `EnvelopeEncryptor` síncrono o nativo
correspondiente y hace fallar el arranque cuando falta. Los registros protegidos no se pueden
leer en modo `none`; la migración de datos y las claves de lectura retenidas se planifican antes
de cambiar el modo. Se recomienda revisar la [protección de almacenes técnicos](../../../axiz-docs/docs/guias/proteccion-de-almacenes-tecnicos.md).

JDBC es una integración separada: se instala `axiz-saga-jdbc-spring-boot-starter`.
La base neutral requiere un `SagaStateStore`; no instala un proveedor de base de datos.
