<!-- GENERADO por scripts/sync_reference.py desde axiz-framework: no editar -->
# idempotency-redis

Almacén de idempotencia en ElastiCache (Redis/Valkey)

```bash
axiz add idempotency-redis
```

|  |  |
|---|---|
| Starter | `pe.axiz:axiz-idempotency-spring-boot-starter` |
| Stacks del catálogo | servlet, reactive |
| Agrega también | [`idempotency`](idempotency.md) |
| Dependencias | `pe.axiz:axiz-idempotency-redis-spring-boot-starter` |
| Incluida por defecto en | ningún arquetipo |

## Propiedades que agrega

Valores por defecto seguros en `application.yaml`; los valores de cada ambiente van en el ConfigMap del servicio y los secretos en el secret store.

```yaml
axiz.idempotency.store: redis
spring.data.redis.url: ${REDIS_URL:redis://localhost:6379}
```

## Llaves del ConfigMap por ambiente

- `spring.data.redis.url`

## Uso

```java
axiz.idempotency.store: redis
```

## Módulos

- [`axiz-idempotency-spring-boot-autoconfigure`](../referencia/spring/axiz-idempotency-spring-boot-autoconfigure.md)
- [`axiz-core-api`](../referencia/core/axiz-core-api.md)
- [`axiz-core-commons`](../referencia/core/axiz-core-commons.md)
- [`axiz-core-concurrent`](../referencia/core/axiz-core-concurrent.md)
- [`axiz-core-context`](../referencia/core/axiz-core-context.md)
- [`axiz-core-crypto`](../referencia/core/axiz-core-crypto.md)
- [`axiz-core-errors`](../referencia/core/axiz-core-errors.md)
- [`axiz-core-idempotency`](../referencia/core/axiz-core-idempotency.md)
- [`axiz-core-json`](../referencia/core/axiz-core-json.md)
