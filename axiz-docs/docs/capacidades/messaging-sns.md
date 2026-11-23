<!-- GENERADO por scripts/sync_reference.py desde axiz-framework: no editar -->
# messaging-sns

Publicación CloudEvents en tópicos SNS (difusión)

```bash
axiz add messaging-sns
```

|  |  |
|---|---|
| Starter | `pe.axiz:axiz-messaging-sns-spring-boot-starter` |
| Stacks del catálogo | servlet, reactive |
| Agrega también | [`messaging`](messaging.md) |
| Incluida por defecto en | ningún arquetipo |

Perfil `local` (`application-local.yaml`):

```yaml
axiz.aws.endpoint: http://localhost:4566
```

## Llaves del ConfigMap por ambiente

- `axiz.messaging.destinations`

## Uso

```java
axiz.messaging.destinations.notifications: arn:aws:sns:<region>:<account>:notifications
```

## Módulos

- [`axiz-messaging-sns-spring-boot-autoconfigure`](../referencia/spring/axiz-messaging-sns-spring-boot-autoconfigure.md)
- [`axiz-core-commons`](../referencia/core/axiz-core-commons.md)
- [`axiz-core-messaging`](../referencia/core/axiz-core-messaging.md)
