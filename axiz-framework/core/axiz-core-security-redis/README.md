<!-- generated -->
# axiz-core-security-redis

Distributed rate limiter and replay-protection nonce store on Redis (ElastiCache).

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
| `RedisNonceStore` | Nonces of signed requests, accepted once across every instance (`SET NX PX`); fails closed if Redis is down. |

## Tests

Unit tests live in `src/test/java` and document the expected behavior; run them with `./mvnw -pl core/axiz-core-security-redis verify`.
