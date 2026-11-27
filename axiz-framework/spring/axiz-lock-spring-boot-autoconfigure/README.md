<!-- generated -->
# axiz-lock-spring-boot-autoconfigure

Locks distribuidos sobre Redis o DynamoDB con la plantilla de lock (autoconfiguración).

## Dependencia

```xml
<dependency>
  <groupId>pe.axiz</groupId>
  <artifactId>axiz-lock-spring-boot-autoconfigure</artifactId>
</dependency>
```

La versión la gestiona `axiz-bom` (los servicios heredan `axiz-parent-service`).

## Tipos principales

| Tipo | Propósito |
|---|---|
| `AxizLockAutoConfiguration` | Locks con lease y renovación (`LockTemplate`) sobre Redis o DynamoDB. |
| `AxizLockProperties` | Locks distribuidos (`axiz.lock.*`) para serializar el trabajo sobre un recurso. |

Tipos relacionados: `AxizLockDynamoDbAutoConfiguration` están en `axiz-lock-dynamodb-spring-boot-autoconfigure`; `AxizLockRedisAutoConfiguration` están en `axiz-lock-redis-spring-boot-autoconfigure`.

## Pruebas

Las pruebas unitarias están en `src/test/java` y documentan el comportamiento esperado; se ejecutan con `./mvnw -pl spring/axiz-lock-spring-boot-autoconfigure verify`.
