<!-- GENERADO por scripts/sync_reference.py desde axiz-framework: no editar -->
# cache-redis

Caché L2 en ElastiCache (Redis/Valkey), tolerante a caídas de Redis

```bash
axiz add cache-redis
```

|  |  |
|---|---|
| Starter | `pe.axiz:axiz-cache-spring-boot-starter` |
| Stacks del catálogo | servlet, reactive |
| Agrega también | [`cache`](cache.md) |
| Dependencias | `pe.axiz:axiz-cache-redis-spring-boot-starter` |
| Incluida por defecto en | ningún arquetipo |

## Propiedades que agrega

Valores por defecto seguros en `application.yaml`; los valores de cada ambiente van en el ConfigMap del servicio y los secretos en el secret store.

```yaml
axiz.cache.redis: true
spring.data.redis.url: ${REDIS_URL:redis://localhost:6379}
```

## Llaves del ConfigMap por ambiente

- `spring.data.redis.url`

## Uso

```java
@AxizCacheable("customers")
```

## Módulos

- [`axiz-cache-spring-boot-autoconfigure`](../referencia/spring/axiz-cache-spring-boot-autoconfigure.md)
- [`axiz-core-cache`](../referencia/core/axiz-core-cache.md)
- [`axiz-core-security`](../referencia/core/axiz-core-security.md)
- [`axiz-core-tenancy`](../referencia/core/axiz-core-tenancy.md)
