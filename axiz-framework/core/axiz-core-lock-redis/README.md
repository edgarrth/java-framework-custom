<!-- generated -->
# axiz-core-lock-redis

Distributed lock on Redis.

## Dependency

```xml
<dependency>
  <groupId>pe.axiz</groupId>
  <artifactId>axiz-core-lock-redis</artifactId>
</dependency>
```

The version is managed by `axiz-bom` (services inherit `axiz-parent-service`).

## Main types

| Type | Purpose |
|---|---|
| `RedisDistributedLock` | Lock on Redis: `SET key token NX PX lease`; renew and release are Lua scripts that act only when the stored token is ours, so an expired owner never touches the next owner's lock. |

## Tests

Unit tests live in `src/test/java` and document the expected behavior; run them with `./mvnw -pl core/axiz-core-lock-redis verify`.
