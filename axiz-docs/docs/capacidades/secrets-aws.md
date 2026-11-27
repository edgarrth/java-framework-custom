<!-- GENERADO por scripts/sync_reference.py desde axiz-framework: no editar -->
# secrets-aws

Fuentes explícitas de Secrets Manager y Parameter Store con identidad de AWS compartida

```bash
axiz add secrets-aws
```

|  |  |
|---|---|
| Starter | `pe.axiz:axiz-secrets-aws-spring-boot-starter` |
| Stacks del catálogo | servlet, reactive |
| Agrega también | [`secrets`](secrets.md) |
| Incluida por defecto en | ningún arquetipo |

## Propiedades que agrega

Valores por defecto seguros en `application.yaml`; los valores de cada ambiente van en el ConfigMap del servicio y los secretos en el secret store.

```yaml
axiz.secrets.secrets-manager.enabled: true
```

Perfil `local` (`application-local.yaml`):

```yaml
axiz.aws.endpoint: http://localhost:4566
```

## Módulos

- [`axiz-secrets-aws-spring-boot-autoconfigure`](../referencia/spring/axiz-secrets-aws-spring-boot-autoconfigure.md)
- [`axiz-core-secrets`](../referencia/core/axiz-core-secrets.md)
