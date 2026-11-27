<!-- GENERADO por scripts/sync_reference.py desde axiz-framework: no editar -->
# idempotency

Idempotency-Key para POST/PATCH con respuestas guardadas (en memoria solo en local/test; los ambientes compartidos requieren idempotency-redis o idempotency-dynamodb)

```bash
axiz add idempotency
```

|  |  |
|---|---|
| Starter | `pe.axiz:axiz-idempotency-spring-boot-starter` |
| Stacks del catálogo | servlet, reactive |
| Incluida por defecto en | gateway |

## Propiedades que agrega

Valores por defecto seguros en `application.yaml`; los valores de cada ambiente van en el ConfigMap del servicio y los secretos en el secret store.

```yaml
axiz.idempotency.retention: 24h
```

## Uso

```java
@Idempotent
@PostMapping ResponseEntity<Order> create(...)
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
