<!-- GENERADO por scripts/sync_reference.py desde axiz-framework: no editar -->
# config-aws

Arranque opcional desde AWS Parameter Store con ambiente y destino elegidos por el proceso

```bash
axiz add config-aws
```

|  |  |
|---|---|
| Starter | `pe.axiz:axiz-config-aws-spring-boot-starter` |
| Stacks del catálogo | servlet, reactive |
| Agrega también | [`config`](config.md) |
| Incluida por defecto en | ningún arquetipo |

## Uso

```java
# Se definen en el proceso, antes de Config Data; no en application.yaml.
AXIZ_CONFIG_PARAMETER_STORE_ENABLED=true
AXIZ_CONFIG_PARAMETER_STORE_APPLICATION=payments
AXIZ_CONFIG_ENVIRONMENT=prod
```

## Módulos

- [`axiz-config-aws-spring-boot-autoconfigure`](../referencia/spring/axiz-config-aws-spring-boot-autoconfigure.md)
- [`axiz-core-commons`](../referencia/core/axiz-core-commons.md)
