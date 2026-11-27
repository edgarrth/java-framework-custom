<!-- GENERADO por scripts/sync_reference.py desde axiz-framework: no editar -->
# axiz-scheduling-spring-boot-autoconfigure

Ejecución única por clúster de tareas programadas con ShedLock (JDBC o DynamoDB) (autoconfiguración).

## Dependencia

```xml
<dependency>
  <groupId>pe.axiz</groupId>
  <artifactId>axiz-scheduling-spring-boot-autoconfigure</artifactId>
</dependency>
```

La versión la gestiona `axiz-bom` (los servicios heredan `axiz-parent-service`).

## Tipos principales

| Tipo | Propósito |
|---|---|
| `AxizSchedulingAutoConfiguration` | `@Scheduled` con ShedLock: las tareas se anotan con `@SchedulerLock(name = ...)` para que solo una réplica las ejecute. |
| `AxizSchedulingProperties` | Tareas programadas con una sola ejecución por clúster (`axiz.scheduling.*`). |

Tipos relacionados: `AxizSchedulingDynamoDbAutoConfiguration` están en `axiz-scheduling-dynamodb-spring-boot-autoconfigure`; `AxizSchedulingR2dbcAutoConfiguration` están en `axiz-scheduling-r2dbc-spring-boot-autoconfigure`.

## Pruebas

Las pruebas unitarias están en `src/test/java` y documentan el comportamiento esperado; se ejecutan con `./mvnw -pl spring/axiz-scheduling-spring-boot-autoconfigure verify`.
