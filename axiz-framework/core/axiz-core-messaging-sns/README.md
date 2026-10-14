<!-- generated -->
# axiz-core-messaging-sns

Amazon SNS publisher adapter (standard and FIFO).

## Dependency

```xml
<dependency>
  <groupId>pe.axiz</groupId>
  <artifactId>axiz-core-messaging-sns</artifactId>
</dependency>
```

The version is managed by `axiz-bom` (services inherit `axiz-parent-service`).

## Main types

| Type | Purpose |
|---|---|
| `SnsEventPublisher` | Publishes CloudEvents (structured JSON) to Amazon SNS topics (fan-out). |

## Tests

Unit tests live in `src/test/java` and document the expected behavior; run them with `./mvnw -pl core/axiz-core-messaging-sns verify`.
