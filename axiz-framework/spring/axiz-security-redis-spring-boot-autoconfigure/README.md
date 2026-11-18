# axiz-security-redis-spring-boot-autoconfigure

Redis integration for cluster-wide rate limiting.

This module owns the `pe.axiz.spring.security.redis` API. It has no application framework or provider dependency.

## Selection

Versions are managed by `axiz-bom`; applications inherit `axiz-parent-service`.

```xml
<dependency>
  <groupId>pe.axiz</groupId>
  <artifactId>axiz-security-redis-spring-boot-starter</artifactId>
</dependency>
```

The paired starter selects this provider. Application code uses the capability ports; do not
import auto-configuration classes into controllers, application services or domain models.

## Types

Public types declared by this module:

- `AxizSecurityRedisAutoConfiguration`
- `AxizSecurityRedisProperties`

## Configuration

Owned property prefixes: `axiz.security.rate-limit.redis.*`.

## Catalog usage

```bash
axiz add security-redis
```

Required capabilities: `security`.

The catalog supplies the following properties. Environment placeholders must resolve in the
service configuration; they are not credentials or provisioned infrastructure.

```yaml
axiz.security.rate-limit.enabled: true
axiz.security.rate-limit.store: redis
axiz.security.rate-limit.rules[0].name: api
axiz.security.rate-limit.rules[0].paths:
- /**
axiz.security.rate-limit.rules[0].limit: 100
axiz.security.rate-limit.rules[0].window: 1m
axiz.security.rate-limit.rules[0].fail-open: false
```

## Operations

Configure an authenticated TLS Redis/Valkey endpoint with bounded connection and command
timeouts. Size capacity and eviction according to the capability: coordination/idempotency
state cannot use cache eviction semantics. In-memory substitutes are limited to explicitly
local/test/ci environments.

## Verification

From `axiz-framework`:

```bash
./mvnw -pl spring/axiz-security-redis-spring-boot-autoconfigure -am test
```

The full acceptance gate is `./mvnw clean install -Pquality -Daxiz.it.requireDocker=true`.
Provider integration tests live in `quality/axiz-store-integration-tests` and require Docker.
