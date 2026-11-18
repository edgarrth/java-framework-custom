# axiz-messaging-kafka-spring-boot-starter

KAFKA CloudEvents messaging.

This module owns the `pe.axiz.spring.messaging.kafka.starter` API.

## Selection

Versions are managed by `axiz-bom`; applications inherit `axiz-parent-service`.

```xml
<dependency>
  <groupId>pe.axiz</groupId>
  <artifactId>axiz-messaging-kafka-spring-boot-starter</artifactId>
</dependency>
```

This artifact composes dependencies; its wiring and operational contract are documented in
[`axiz-messaging-kafka-spring-boot-autoconfigure`](../axiz-messaging-kafka-spring-boot-autoconfigure/README.md).

## Catalog usage

```bash
axiz add messaging-kafka
```

Required capabilities: `messaging`.

The catalog supplies the following properties. Environment placeholders must resolve in the
service configuration; they are not credentials or provisioned infrastructure.

```yaml
spring.kafka.bootstrap-servers: ${KAFKA_BOOTSTRAP_SERVERS:localhost:9092}
```

## Operations

Provision topics and broker access with TLS/SASL, bounded transport timeouts and durability
settings suited to the event contract. Delivery may be repeated; consumers use stable event
IDs and inbox deduplication. A producer acknowledgement is not a business transaction commit.
