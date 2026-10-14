<!-- generated -->
# axiz-framework-batch-api

Batch style on Spring Batch: restartable jobs, chunked steps, files on S3, CronJob or ShedLock execution (contracts, plain Java).

## Dependency

```xml
<dependency>
  <groupId>pe.axiz</groupId>
  <artifactId>axiz-framework-batch-api</artifactId>
</dependency>
```

The version is managed by `axiz-bom` (services inherit `axiz-parent-service`).

## Main types

| Type | Purpose |
|---|---|
| `CsvRecordReader` | CSV records mapped to objects; the header can be skipped and blank lines are ignored. |
| `FixedWidthRecordReader` | Fixed-width records (legacy files) parsed with a layout; header and trailer lines are recognized by predicates and skipped (the trailer is kept for control totals). |
| `JobOutcome` | Summary of a finished job used for metrics, audit and the CronJob exit code. |
| `RecordReader` | Sequential reader of file records with line tracking, so jobs can restart from the last committed record and errors point to the offending line. |

## Tests

Unit tests live in `src/test/java` and document the expected behavior; run them with `./mvnw -pl frameworks/batch/axiz-framework-batch-api verify`.
