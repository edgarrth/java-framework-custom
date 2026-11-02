<!-- generated -->
# axiz-structure-batch-spring-boot-starter

Batch style on Spring Batch: restartable jobs, chunked steps, files on S3, CronJob or ShedLock execution.

## Dependency

```xml
<dependency>
  <groupId>pe.axiz</groupId>
  <artifactId>axiz-structure-batch-spring-boot-starter</artifactId>
</dependency>
```

The version is managed by `axiz-bom` (services inherit `axiz-parent-service`).

## Main types

| Type | Purpose |
|---|---|
| `AxizBatchAutoConfiguration` | Batch style on Spring Batch: every job reports metrics, audit and a summary line; readers for files in S3 restart from the last committed chunk. |
| `AxizBatchEnvironmentPostProcessor` | Batch defaults: no web server (CronJob), the job runs on startup and the process exits with its status; the Spring Batch schema is managed by Flyway outside local runs. |
| `ObservedJobListener` | Job metrics (`batch.run` event with duration and records), audit of each run and one summary log line. |
| `ObservedJobListenerPostProcessor` | Registers `ObservedJobListener` on every job bean. |
| `StorageRecordItemReader` | Restartable Spring Batch reader over a file in object storage (S3): the item count is saved in the step execution context, so a failed job resumes after the last committed chunk. |

## Tests

Unit tests live in `src/test/java` and document the expected behavior; run them with `./mvnw -pl structures/batch/axiz-structure-batch-spring-boot-starter verify`.
