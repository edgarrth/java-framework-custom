<!-- generated -->
# axiz-scheduling-spring-boot-autoconfigure

Single execution per cluster for scheduled tasks with ShedLock (JDBC or DynamoDB) (auto-configuration).

## Dependency

```xml
<dependency>
  <groupId>pe.axiz</groupId>
  <artifactId>axiz-scheduling-spring-boot-autoconfigure</artifactId>
</dependency>
```

The version is managed by `axiz-bom` (services inherit `axiz-parent-service`).

## Main types

| Type | Purpose |
|---|---|
| `AxizSchedulingAutoConfiguration` | `@Scheduled` with ShedLock: annotate tasks with `@SchedulerLock(name = ...)` so only one replica runs them. |
| `AxizSchedulingDynamoDbAutoConfiguration` | ShedLock on DynamoDB (`axiz.scheduling.store=dynamodb`). |
| `AxizSchedulingProperties` | Scheduled tasks with a single execution per cluster (`axiz.scheduling.*`). |
| `AxizSchedulingR2dbcAutoConfiguration` | ShedLock on Aurora over R2DBC (`axiz.scheduling.store=r2dbc`) for reactive services; the lock table comes from the service's Flyway migrations. |

## Tests

Unit tests live in `src/test/java` and document the expected behavior; run them with `./mvnw -pl spring/axiz-scheduling-spring-boot-autoconfigure verify`.
