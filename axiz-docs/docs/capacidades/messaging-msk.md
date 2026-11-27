<!-- GENERADO por scripts/sync_reference.py desde axiz-framework: no editar -->
# messaging-msk

Autenticación IAM de MSK mediante la política compartida de credenciales de AWS

```bash
axiz add messaging-msk
```

|  |  |
|---|---|
| Starter | `pe.axiz:axiz-messaging-msk-spring-boot-starter` |
| Stacks del catálogo | servlet, reactive |
| Agrega también | [`messaging-kafka`](messaging-kafka.md) |
| Incluida por defecto en | ningún arquetipo |

## Propiedades que agrega

Valores por defecto seguros en `application.yaml`; los valores de cada ambiente van en el ConfigMap del servicio y los secretos en el secret store.

```yaml
axiz.messaging.kafka.authentication: msk
```

Perfil `local` (`application-local.yaml`):

```yaml
axiz.messaging.kafka.authentication: configured
```

## Llaves del ConfigMap por ambiente

- `axiz.messaging.kafka.authentication`

## Módulos

- [`axiz-messaging-msk-spring-boot-autoconfigure`](../referencia/spring/axiz-messaging-msk-spring-boot-autoconfigure.md)
- [`axiz-core-commons`](../referencia/core/axiz-core-commons.md)
- [`axiz-core-messaging`](../referencia/core/axiz-core-messaging.md)
