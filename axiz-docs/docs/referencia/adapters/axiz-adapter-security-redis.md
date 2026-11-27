<!-- GENERADO por scripts/sync_reference.py desde axiz-framework: no editar -->
# axiz-adapter-security-redis

Limitador de tasa distribuido y almacén de nonces contra repetición sobre Redis (ElastiCache).

## Dependencia

```xml
<dependency>
  <groupId>pe.axiz</groupId>
  <artifactId>axiz-adapter-security-redis</artifactId>
</dependency>
```

La versión la gestiona `axiz-bom` (los servicios heredan `axiz-parent-service`).

## Tipos principales

| Tipo | Propósito |
|---|---|
| `RedisRateLimiter` | Limitador de tasa distribuido de ventana fija sobre Redis. |
| `RedisNonceStore` | Nonces de peticiones firmadas, aceptados una sola vez entre todas las instancias (`SET NX PX`); falla en modo cerrado si Redis no está disponible. |

## Pruebas

Las pruebas unitarias están en `src/test/java` y documentan el comportamiento esperado; se ejecutan con `./mvnw -pl adapters/axiz-adapter-security-redis verify`.
