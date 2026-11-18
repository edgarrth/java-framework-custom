# axiz-secrets-file-spring-boot-starter

FILE secret sources.

This module owns the `pe.axiz.spring.secrets.file.starter` API.

## Selection

Versions are managed by `axiz-bom`; applications inherit `axiz-parent-service`.

```xml
<dependency>
  <groupId>pe.axiz</groupId>
  <artifactId>axiz-secrets-file-spring-boot-starter</artifactId>
</dependency>
```

This artifact composes dependencies; its wiring and operational contract are documented in
[`axiz-secrets-file-spring-boot-autoconfigure`](../axiz-secrets-file-spring-boot-autoconfigure/README.md).

## Catalog usage

```bash
axiz add secrets-file
```

Required capabilities: `secrets`.

The catalog supplies the following properties. Environment placeholders must resolve in the
service configuration; they are not credentials or provisioned infrastructure.

```yaml
axiz.secrets.directory: /var/run/secrets/axiz
```
