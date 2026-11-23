<!-- GENERADO por scripts/sync_reference.py desde axiz-framework: no editar -->
# tokenization-vault

Bóveda central de tokens con políticas explícitas, auditoría durable y presupuestos distribuidos

```bash
axiz add tokenization-vault
```

|  |  |
|---|---|
| Starter | `pe.axiz:axiz-tokenization-vault-spring-boot-starter` |
| Stacks del catálogo | servlet, reactive |
| Agrega también | [`crypto`](crypto.md), [`audit`](audit.md), [`security`](security.md) |
| Incluida por defecto en | ningún arquetipo |

## Llaves del ConfigMap por ambiente

- `axiz.tokenization.vault.auth`
- `axiz.tokenization.vault.types`

## Uso

```java
# Se aportan los SPI verificados de mapeo de claims, políticas de tipos, política de acceso y conciliación.
# El servidor se niega a arrancar si falta un colaborador requerido o un control de claves.
```

## Módulos

- [`axiz-tokenization-vault-spring-boot-autoconfigure`](../referencia/spring/axiz-tokenization-vault-spring-boot-autoconfigure.md)
- [`axiz-core-api`](../referencia/core/axiz-core-api.md)
- [`axiz-core-audit`](../referencia/core/axiz-core-audit.md)
- [`axiz-core-commons`](../referencia/core/axiz-core-commons.md)
- [`axiz-core-concurrent`](../referencia/core/axiz-core-concurrent.md)
- [`axiz-core-crypto`](../referencia/core/axiz-core-crypto.md)
- [`axiz-core-errors`](../referencia/core/axiz-core-errors.md)
- [`axiz-core-json`](../referencia/core/axiz-core-json.md)
- [`axiz-core-security`](../referencia/core/axiz-core-security.md)
- [`axiz-core-tokenization`](../referencia/core/axiz-core-tokenization.md)
- [`axiz-core-tokenization-vault`](../referencia/core/axiz-core-tokenization-vault.md)
