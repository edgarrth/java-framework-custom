# axiz-secrets-file-spring-boot-autoconfigure

FILE integration for secret resolution.

This module owns the `pe.axiz.spring.secrets.file` API.

## Selection

Versions are managed by `axiz-bom`; applications inherit `axiz-parent-service`.

```xml
<dependency>
  <groupId>pe.axiz</groupId>
  <artifactId>axiz-secrets-file-spring-boot-starter</artifactId>
</dependency>
```

The paired starter selects this provider. Application code uses the capability ports; do not
import auto-configuration classes into controllers, application services or domain models.

## Types

Public types declared by this module:

- `AxizSecretsFileAutoConfiguration`
- `AxizSecretsFileProperties`
- `SecretsConfigTreeEnvironmentPostProcessor`

## Configuration

Owned property prefixes: `axiz.secrets.*`.

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

## Verification

From `axiz-framework`:

```bash
./mvnw -pl spring/axiz-secrets-file-spring-boot-autoconfigure -am test
```

The full acceptance gate is `./mvnw clean install -Pquality -Daxiz.it.requireDocker=true`.
Provider integration tests live in `quality/axiz-store-integration-tests` and require Docker.
