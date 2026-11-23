<!-- GENERADO por scripts/sync_reference.py desde axiz-framework: no editar -->
# jpa-aws

AWS JDBC wrapper para failover de Aurora y autenticación IAM opcional

```bash
axiz add jpa-aws
```

|  |  |
|---|---|
| Starter | `pe.axiz:axiz-jpa-aws-spring-boot-starter` |
| Stacks del catálogo | servlet |
| Agrega también | [`jpa`](jpa.md) |
| Incluida por defecto en | ningún arquetipo |

## Propiedades que agrega

Valores por defecto seguros en `application.yaml`; los valores de cada ambiente van en el ConfigMap del servicio y los secretos en el secret store.

```yaml
axiz.jpa.aws-wrapper.enabled: ${AXIZ_JPA_AWS_WRAPPER:true}
axiz.jpa.aws-wrapper.iam: ${AXIZ_JPA_IAM:false}
```

## Llaves del ConfigMap por ambiente

- `axiz.jpa.aws-wrapper.enabled`
- `axiz.jpa.aws-wrapper.iam`

## Módulos

- [`axiz-jpa-aws-spring-boot-autoconfigure`](../referencia/spring/axiz-jpa-aws-spring-boot-autoconfigure.md)
- [`axiz-core-commons`](../referencia/core/axiz-core-commons.md)
