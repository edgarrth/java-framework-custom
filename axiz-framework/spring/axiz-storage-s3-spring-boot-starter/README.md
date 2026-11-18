# axiz-storage-s3-spring-boot-starter

S3 object storage with bounded signed URLs.

This module owns the `pe.axiz.spring.storage.s3.starter` API. It has no application framework or provider dependency.

## Selection

Versions are managed by `axiz-bom`; applications inherit `axiz-parent-service`.

```xml
<dependency>
  <groupId>pe.axiz</groupId>
  <artifactId>axiz-storage-s3-spring-boot-starter</artifactId>
</dependency>
```

This artifact composes dependencies; its wiring and operational contract are documented in
[`axiz-storage-s3-spring-boot-autoconfigure`](../axiz-storage-s3-spring-boot-autoconfigure/README.md).

## Catalog usage

```bash
axiz add storage-s3
```

Required capabilities: `storage`.

The catalog supplies the following properties. Environment placeholders must resolve in the
service configuration; they are not credentials or provisioned infrastructure.

```yaml
axiz.storage.bucket: ${AXIZ_STORAGE_BUCKET:local-bucket}
```

## Operations

Provide region, workload identity, TLS and least-privilege permissions for the selected AWS
resources. Provision production tables, queues, topics, keys and buckets through reviewed IaC.
LocalStack tests exercise behavior but do not attest deployed IAM or service durability.
Never place long-lived credentials in generated YAML.
