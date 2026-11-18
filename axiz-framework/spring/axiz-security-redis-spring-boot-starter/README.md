# axiz-security-redis-spring-boot-starter

Cluster-wide rate limiting on Redis.

This module owns the `pe.axiz.spring.security.redis.starter` API. It has no application framework or provider dependency.

## Selection

Versions are managed by `axiz-bom`; applications inherit `axiz-parent-service`.

```xml
<dependency>
  <groupId>pe.axiz</groupId>
  <artifactId>axiz-security-redis-spring-boot-starter</artifactId>
</dependency>
```

This artifact composes dependencies; its wiring and operational contract are documented in
[`axiz-security-redis-spring-boot-autoconfigure`](../axiz-security-redis-spring-boot-autoconfigure/README.md).

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
