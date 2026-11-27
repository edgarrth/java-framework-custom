<!-- generated -->
# axiz-adapter-idempotency-redis

Almacén de idempotencia en Redis (ElastiCache).

## Dependencia

```xml
<dependency>
  <groupId>pe.axiz</groupId>
  <artifactId>axiz-adapter-idempotency-redis</artifactId>
</dependency>
```

La versión la gestiona `axiz-bom` (los servicios heredan `axiz-parent-service`).

## Tipos principales

| Tipo | Propósito |
|---|---|
| `RedisIdempotencyStore` | Almacén de idempotencia en Redis/Valkey: `SET NX PX` para iniciar; la finalización y la liberación corren como scripts Lua que comprueban, en el mismo paso atómico, que el registro sigue IN_PROGRESS y pertenece al token del llamador. |

## Pruebas

Las pruebas unitarias están en `src/test/java` y documentan el comportamiento esperado; se ejecutan con `./mvnw -pl adapters/axiz-adapter-idempotency-redis verify`.
