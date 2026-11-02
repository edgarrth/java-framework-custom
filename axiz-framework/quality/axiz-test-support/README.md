<!-- generated -->
# axiz-test-support

Test fixtures for layer 1: mutable clock, recording publisher, metric assertions and an in-memory R2DBC fake.

## Dependency

```xml
<dependency>
  <groupId>pe.axiz</groupId>
  <artifactId>axiz-test-support</artifactId>
</dependency>
```

The version is managed by `axiz-bom` (services inherit `axiz-parent-service`).

## Main types

| Type | Purpose |
|---|---|
| `MetricAssertions` | Reads generic event metrics in tests without depending on a test framework. |
| `MutableClock` | Clock that tests can move forward or set. |
| `RecordingEventPublisher` | Publisher that records events; can be switched to fail to test retries. |
| `FakeR2dbc` | In-memory R2DBC driver for unit tests, reporting itself as PostgreSQL (`$n` bind markers): statements are matched by a regular expression on their SQL and answered by scripted functions of the bound values; every execution, bind and transaction event is recorded for assertions. |

## Tests

Unit tests live in `src/test/java` and document the expected behavior; run them with `./mvnw -pl quality/axiz-test-support verify`.
