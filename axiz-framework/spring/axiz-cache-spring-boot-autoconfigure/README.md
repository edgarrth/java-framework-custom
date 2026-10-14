<!-- generated -->
# axiz-cache-spring-boot-autoconfigure

@AxizCacheable with L1 Caffeine and L2 Redis (ElastiCache), TTL per cache and failure tolerance (auto-configuration).

## Dependency

```xml
<dependency>
  <groupId>pe.axiz</groupId>
  <artifactId>axiz-cache-spring-boot-autoconfigure</artifactId>
</dependency>
```

The version is managed by `axiz-bom` (services inherit `axiz-parent-service`).

## Main types

| Type | Purpose |
|---|---|
| `AxizCacheAutoConfiguration` | `AxizCacheable` and named caches on L1 (Caffeine) plus L2 (ElastiCache) when configured. |
| `AxizCacheCaffeineAutoConfiguration` | L1 on Caffeine when it is on the classpath (the starter brings it). |
| `AxizCacheProperties` | Two-level cache (`axiz.cache.*`): L1 in memory per instance, L2 on ElastiCache (Redis or Valkey) shared by the replicas. |
| `AxizCacheRedisAutoConfiguration` | L2 cache on ElastiCache using Boot's Redis connection settings (`spring.data.redis.*`, TLS with certificate verification). |
| `AxizCacheableInterceptor` | Applies `AxizCacheable`: the key is the method name plus the arguments; values are stored as JSON with their full generic type; `null` results are not cached; `Mono` and `Flux` results are cached when they complete, without blocking the event loop. |
| `AxizCaches` | Named caches over the shared two-level store, with the TTL configured per name. |
| `CaffeineByteCache` | L1 on Caffeine with a TTL per entry and size-based eviction. |
| `JacksonValueCodec` | JSON values with the application mapper. |

## Tests

Unit tests live in `src/test/java` and document the expected behavior; run them with `./mvnw -pl spring/axiz-cache-spring-boot-autoconfigure verify`.
