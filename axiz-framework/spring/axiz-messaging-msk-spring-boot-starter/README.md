# axiz-messaging-msk-spring-boot-starter

MSK CloudEvents messaging.

This module owns the `pe.axiz.spring.messaging.msk.starter` API.

## Selection

Versions are managed by `axiz-bom`; applications inherit `axiz-parent-service`.

```xml
<dependency>
  <groupId>pe.axiz</groupId>
  <artifactId>axiz-messaging-msk-spring-boot-starter</artifactId>
</dependency>
```

This artifact composes dependencies; its wiring and operational contract are documented in
[`axiz-messaging-msk-spring-boot-autoconfigure`](../axiz-messaging-msk-spring-boot-autoconfigure/README.md).

## Catalog usage

```bash
axiz add messaging-msk
```

Required capabilities: `messaging-kafka`.

The catalog supplies the following properties. Environment placeholders must resolve in the
service configuration; they are not credentials or provisioned infrastructure.

```yaml
axiz.messaging.kafka.authentication: msk
```

## Operations

Provide region, workload identity, TLS and least-privilege permissions for the selected AWS
resources. Provision production tables, queues, topics, keys and buckets through reviewed IaC.
LocalStack tests exercise behavior but do not attest deployed IAM or service durability.
Never place long-lived credentials in generated YAML.
