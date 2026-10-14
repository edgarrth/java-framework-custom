<!-- generated -->
# axiz-core-security-redis

Distributed fixed-window rate limiter on Redis (ElastiCache).

## Dependency

```xml
<dependency>
  <groupId>pe.axiz</groupId>
  <artifactId>axiz-core-security-redis</artifactId>
</dependency>
```

The version is managed by `axiz-bom` (services inherit `axiz-parent-service`).

## Main types

| Type | Purpose |
|---|---|
| `RedisRateLimiter` | Distributed fixed-window rate limiter on Redis. |

## Tests

Unit tests live in `src/test/java` and document the expected behavior; run them with `./mvnw -pl core/axiz-core-security-redis verify`.
