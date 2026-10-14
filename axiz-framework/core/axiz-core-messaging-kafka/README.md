<!-- generated -->
# axiz-core-messaging-kafka

Amazon MSK (Kafka) publisher and consumer adapter with IAM authentication.

## Dependency

```xml
<dependency>
  <groupId>pe.axiz</groupId>
  <artifactId>axiz-core-messaging-kafka</artifactId>
</dependency>
```

The version is managed by `axiz-bom` (services inherit `axiz-parent-service`).

## Main types

| Type | Purpose |
|---|---|
| `KafkaEnvelopes` | Maps consumed Kafka records back to envelopes. |
| `KafkaEventPublisher` | Publishes CloudEvents (binary mode) to Amazon MSK. |
| `MskIam` | Client properties for Amazon MSK with IAM authentication. |

## Tests

Unit tests live in `src/test/java` and document the expected behavior; run them with `./mvnw -pl core/axiz-core-messaging-kafka verify`.
