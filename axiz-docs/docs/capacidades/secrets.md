<!-- GENERADO por scripts/sync_reference.py desde axiz-framework: no editar -->
# secrets

Fuentes de secretos ordenadas y caché con TTL acotado, independientes del proveedor

```bash
axiz add secrets
```

|  |  |
|---|---|
| Starter | `pe.axiz:axiz-secrets-spring-boot-starter` |
| Stacks del catálogo | servlet, reactive |
| Incluida por defecto en | business, core, gateway, batch |

## Uso

```java
try (SecretValue apiKey = secretResolver.require("partner-api-key")) { /* use apiKey */ }
```

## Módulos

- [`axiz-secrets-spring-boot-autoconfigure`](../referencia/spring/axiz-secrets-spring-boot-autoconfigure.md)
- [`axiz-core-secrets`](../referencia/core/axiz-core-secrets.md)
