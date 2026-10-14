<!-- generated -->
# axiz-core-cache

Cache port, two-level cache and in-memory implementation.

## Dependency

```xml
<dependency>
  <groupId>pe.axiz</groupId>
  <artifactId>axiz-core-cache</artifactId>
</dependency>
```

The version is managed by `axiz-bom` (services inherit `axiz-parent-service`).

## Main types

| Type | Purpose |
|---|---|
| `AxizCache` | Typed cache facade over a `ByteCache`: prefixes keys (`<service>:<cache>:<key>`), serializes with a `ValueCodec` and loads missing values once per key at a time (single flight) to prevent stampedes. |
| `AxizCacheable` | Caches the result of a method (including `Mono` and `Flux` in reactive services). |
| `ByteCache` | Byte-level cache tier (local memory, Redis). |
| `CacheKeys` | Builds cache keys from method arguments; values containing separators are escaped. |
| `CacheUnavailableException` | The remote cache tier is not reachable. |
| `InMemoryByteCache` | Local cache tier with TTL and a size bound (oldest entries are dropped first when full). |
| `TwoLevelCache` | L1 (local) + L2 (Redis) cache. |
| `ValueCodec` | Serializes cached values (JSON with the service mapper in the starter). |

## Tests

Unit tests live in `src/test/java` and document the expected behavior; run them with `./mvnw -pl core/axiz-core-cache verify`.
