<!-- generated -->
# axiz-core-audit-kafka

Audit publisher for Amazon MSK (Kafka).

## Dependency

```xml
<dependency>
  <groupId>pe.axiz</groupId>
  <artifactId>axiz-core-audit-kafka</artifactId>
</dependency>
```

The version is managed by `axiz-bom` (services inherit `axiz-parent-service`).

## Main types

| Type | Purpose |
|---|---|
| `KafkaAuditPublisher` | Publishes audit events to an Amazon MSK (Kafka) topic. |

## Tests

Unit tests live in `src/test/java` and document the expected behavior; run them with `./mvnw -pl core/axiz-core-audit-kafka verify`.
