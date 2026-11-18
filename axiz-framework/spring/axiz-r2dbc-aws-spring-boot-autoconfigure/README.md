# axiz-r2dbc-aws-spring-boot-autoconfigure

AWS IAM credentials for R2DBC writer and reader pools.

This module owns the `pe.axiz.spring.r2dbc.aws` API.

## Selection

Versions are managed by `axiz-bom`; applications inherit `axiz-parent-service`.

```xml
<dependency>
  <groupId>pe.axiz</groupId>
  <artifactId>axiz-r2dbc-aws-spring-boot-starter</artifactId>
</dependency>
```

The paired starter selects this provider. Application code uses the capability ports; do not
import auto-configuration classes into controllers, application services or domain models.

## Types

Public types declared by this module:

- `AxizR2dbcAwsEnvironmentPostProcessor`
- `AxizR2dbcAwsProperties`
- `AxizR2dbcIamAutoConfiguration`

## Configuration

Owned property prefixes: `axiz.r2dbc.iam.*`.

## Catalog usage

```bash
axiz add r2dbc-aws
```

Required capabilities: `r2dbc`.

The catalog supplies the following properties. Environment placeholders must resolve in the
service configuration; they are not credentials or provisioned infrastructure.

```yaml
axiz.r2dbc.iam.enabled: ${AXIZ_R2DBC_IAM:true}
```

## Operations

Provide region, workload identity, TLS and least-privilege permissions for the selected AWS
resources. Provision production tables, queues, topics, keys and buckets through reviewed IaC.
LocalStack tests exercise behavior but do not attest deployed IAM or service durability.
Never place long-lived credentials in generated YAML.

## Verification

From `axiz-framework`:

```bash
./mvnw -pl spring/axiz-r2dbc-aws-spring-boot-autoconfigure -am test
```

The full acceptance gate is `./mvnw clean install -Pquality -Daxiz.it.requireDocker=true`.
Provider integration tests live in `quality/axiz-store-integration-tests` and require Docker.
