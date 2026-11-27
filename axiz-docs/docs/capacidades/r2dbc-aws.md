<!-- GENERADO por scripts/sync_reference.py desde axiz-framework: no editar -->
# r2dbc-aws

Autenticación IAM de AWS con credenciales independientes de escritura y de lectura

```bash
axiz add r2dbc-aws
```

|  |  |
|---|---|
| Starter | `pe.axiz:axiz-r2dbc-aws-spring-boot-starter` |
| Stacks del catálogo | reactive |
| Agrega también | [`r2dbc`](r2dbc.md) |
| Incluida por defecto en | ningún arquetipo |

## Propiedades que agrega

Valores por defecto seguros en `application.yaml`; los valores de cada ambiente van en el ConfigMap del servicio y los secretos en el secret store.

```yaml
axiz.r2dbc.iam.enabled: ${AXIZ_R2DBC_IAM:true}
```

Perfil `local` (`application-local.yaml`):

```yaml
axiz.r2dbc.iam.enabled: false
```

## Llaves del ConfigMap por ambiente

- `axiz.r2dbc.iam.enabled`

## Módulos

- [`axiz-r2dbc-aws-spring-boot-autoconfigure`](../referencia/spring/axiz-r2dbc-aws-spring-boot-autoconfigure.md)
