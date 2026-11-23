<!-- GENERADO por scripts/sync_reference.py desde axiz-framework: no editar -->
# lock-dynamodb

Locks distribuidos en DynamoDB (leases)

```bash
axiz add lock-dynamodb
```

|  |  |
|---|---|
| Starter | `pe.axiz:axiz-lock-spring-boot-starter` |
| Stacks del catálogo | servlet, reactive |
| Agrega también | [`lock`](lock.md), [`dynamodb`](dynamodb.md) |
| Dependencias | `pe.axiz:axiz-lock-dynamodb-spring-boot-starter` |
| Incluida por defecto en | ningún arquetipo |

## Propiedades que agrega

Valores por defecto seguros en `application.yaml`; los valores de cada ambiente van en el ConfigMap del servicio y los secretos en el secret store.

```yaml
axiz.lock.store: dynamodb
axiz.lock.table: ${axiz.dynamodb.table-prefix}axiz-locks
```

## Uso

```java
axiz.lock.store: dynamodb
```

## Módulos

- [`axiz-lock-spring-boot-autoconfigure`](../referencia/spring/axiz-lock-spring-boot-autoconfigure.md)
- [`axiz-core-commons`](../referencia/core/axiz-core-commons.md)
- [`axiz-core-concurrent`](../referencia/core/axiz-core-concurrent.md)
- [`axiz-core-errors`](../referencia/core/axiz-core-errors.md)
- [`axiz-core-lock`](../referencia/core/axiz-core-lock.md)
