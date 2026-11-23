<!-- GENERADO por scripts/sync_reference.py desde axiz-framework: no editar -->
# lock-redis

Locks distribuidos en ElastiCache (Redis/Valkey)

```bash
axiz add lock-redis
```

|  |  |
|---|---|
| Starter | `pe.axiz:axiz-lock-spring-boot-starter` |
| Stacks del catálogo | servlet, reactive |
| Agrega también | [`lock`](lock.md) |
| Dependencias | `pe.axiz:axiz-lock-redis-spring-boot-starter` |
| Incluida por defecto en | ningún arquetipo |

## Propiedades que agrega

Valores por defecto seguros en `application.yaml`; los valores de cada ambiente van en el ConfigMap del servicio y los secretos en el secret store.

```yaml
axiz.lock.store: redis
spring.data.redis.url: ${REDIS_URL:redis://localhost:6379}
```

## Llaves del ConfigMap por ambiente

- `spring.data.redis.url`

## Uso

```java
axiz.lock.store: redis
```

## Módulos

- [`axiz-lock-spring-boot-autoconfigure`](../referencia/spring/axiz-lock-spring-boot-autoconfigure.md)
- [`axiz-core-commons`](../referencia/core/axiz-core-commons.md)
- [`axiz-core-concurrent`](../referencia/core/axiz-core-concurrent.md)
- [`axiz-core-errors`](../referencia/core/axiz-core-errors.md)
- [`axiz-core-lock`](../referencia/core/axiz-core-lock.md)
