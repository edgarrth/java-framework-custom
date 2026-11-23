<!-- GENERADO por scripts/sync_reference.py desde axiz-framework: no editar -->
# tokenization-vault-dynamodb

Tabla explícita de la bóveda central, unicidad atómica y controles de rotación de claves

```bash
axiz add tokenization-vault-dynamodb
```

|  |  |
|---|---|
| Starter | `pe.axiz:axiz-tokenization-vault-dynamodb-spring-boot-starter` |
| Stacks del catálogo | servlet, reactive |
| Agrega también | [`tokenization-vault`](tokenization-vault.md), [`dynamodb`](dynamodb.md), [`crypto-kms`](crypto-kms.md) |
| Incluida por defecto en | ningún arquetipo |

## Propiedades que agrega

Valores por defecto seguros en `application.yaml`; los valores de cada ambiente van en el ConfigMap del servicio y los secretos en el secret store.

```yaml
axiz.tokenization.vault.dynamodb.table: ${TOKEN_VAULT_TABLE}
```

## Llaves del ConfigMap por ambiente

- `axiz.tokenization.vault.dynamodb.table`

## Uso

```java
# La tabla y los controles de claves se aprovisionan con la identidad de operador separada antes del arranque.
```

## Módulos

- [`axiz-tokenization-vault-dynamodb-spring-boot-autoconfigure`](../referencia/spring/axiz-tokenization-vault-dynamodb-spring-boot-autoconfigure.md)
- [`axiz-core-crypto`](../referencia/core/axiz-core-crypto.md)
- [`axiz-core-errors`](../referencia/core/axiz-core-errors.md)
- [`axiz-core-tokenization-vault`](../referencia/core/axiz-core-tokenization-vault.md)
