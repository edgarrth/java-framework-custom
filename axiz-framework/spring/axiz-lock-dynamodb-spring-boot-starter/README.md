# axiz-lock-dynamodb-spring-boot-starter

Lock with dynamodb.

This module owns the `pe.axiz.spring.lock.dynamodb.starter` API. It has no application framework or provider dependency.

## Selection

Versions are managed by `axiz-bom`; applications inherit `axiz-parent-service`.

```xml
<dependency>
  <groupId>pe.axiz</groupId>
  <artifactId>axiz-lock-dynamodb-spring-boot-starter</artifactId>
</dependency>
```

This artifact composes dependencies; its wiring and operational contract are documented in
[`axiz-lock-dynamodb-spring-boot-autoconfigure`](../axiz-lock-dynamodb-spring-boot-autoconfigure/README.md).

## Operations

Provide region, workload identity, TLS and least-privilege permissions for the selected AWS
resources. Provision production tables, queues, topics, keys and buckets through reviewed IaC.
LocalStack tests exercise behavior but do not attest deployed IAM or service durability.
Never place long-lived credentials in generated YAML.
