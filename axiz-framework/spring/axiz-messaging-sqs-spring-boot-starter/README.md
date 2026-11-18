# axiz-messaging-sqs-spring-boot-starter

SQS CloudEvents messaging.

This module owns the `pe.axiz.spring.messaging.sqs.starter` API.

## Selection

Versions are managed by `axiz-bom`; applications inherit `axiz-parent-service`.

```xml
<dependency>
  <groupId>pe.axiz</groupId>
  <artifactId>axiz-messaging-sqs-spring-boot-starter</artifactId>
</dependency>
```

This artifact composes dependencies; its wiring and operational contract are documented in
[`axiz-messaging-sqs-spring-boot-autoconfigure`](../axiz-messaging-sqs-spring-boot-autoconfigure/README.md).

## Catalog usage

```bash
axiz add messaging-sqs
```

Required capabilities: `messaging`.

The catalog supplies the following properties. Environment placeholders must resolve in the
service configuration; they are not credentials or provisioned infrastructure.

```yaml
axiz.messaging.sqs.wait-time: 20s
```

## Operations

Provide region, workload identity, TLS and least-privilege permissions for the selected AWS
resources. Provision production tables, queues, topics, keys and buckets through reviewed IaC.
LocalStack tests exercise behavior but do not attest deployed IAM or service durability.
Never place long-lived credentials in generated YAML.
