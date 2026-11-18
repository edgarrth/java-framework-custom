# axiz-outbox-r2dbc-spring-boot-autoconfigure

R2DBC transactional outbox persistence.

This module owns the `pe.axiz.spring.outbox.r2dbc` API.

## Selection

Versions are managed by `axiz-bom`; applications inherit `axiz-parent-service`.

```xml
<dependency>
  <groupId>pe.axiz</groupId>
  <artifactId>axiz-outbox-r2dbc-spring-boot-starter</artifactId>
</dependency>
```

The paired starter selects this provider. Application code uses the capability ports; do not
import auto-configuration classes into controllers, application services or domain models.

## Types

Public types declared by this module:

- `AxizOutboxR2dbcAutoConfiguration`
- `AxizOutboxR2dbcProperties`

## Configuration

Owned property prefixes: `axiz.outbox.*`.

## Operations

Provision the capability schema through migrations and configure a bounded connection pool
with verified TLS. Transactional composition requires the same effective resource identity,
not merely two clients pointing to the same database. Select the native R2DBC provider for
reactive flows; JDBC executes on the imperative path.

## Verification

From `axiz-framework`:

```bash
./mvnw -pl spring/axiz-outbox-r2dbc-spring-boot-autoconfigure -am test
```

The full acceptance gate is `./mvnw clean install -Pquality -Daxiz.it.requireDocker=true`.
Provider integration tests live in `quality/axiz-store-integration-tests` and require Docker.
