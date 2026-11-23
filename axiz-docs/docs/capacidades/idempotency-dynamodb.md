<!-- GENERADO por scripts/sync_reference.py desde axiz-framework: no editar -->
# idempotency-dynamodb

Almacén de idempotencia en DynamoDB (escrituras condicionales y TTL)

```bash
axiz add idempotency-dynamodb
```

|  |  |
|---|---|
| Starter | `pe.axiz:axiz-idempotency-spring-boot-starter` |
| Stacks del catálogo | servlet, reactive |
| Agrega también | [`idempotency`](idempotency.md), [`dynamodb`](dynamodb.md) |
| Dependencias | `pe.axiz:axiz-idempotency-dynamodb-spring-boot-starter` |
| Incluida por defecto en | ningún arquetipo |

## Propiedades que agrega

Valores por defecto seguros en `application.yaml`; los valores de cada ambiente van en el ConfigMap del servicio y los secretos en el secret store.

```yaml
axiz.idempotency.store: dynamodb
axiz.idempotency.table: ${axiz.dynamodb.table-prefix}axiz-idempotency
```

## Uso

```java
axiz.idempotency.store: dynamodb
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
