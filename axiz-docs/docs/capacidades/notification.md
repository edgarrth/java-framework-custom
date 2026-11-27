<!-- GENERADO por scripts/sync_reference.py desde axiz-framework: no editar -->
# notification

Notificaciones con plantilla (correo, SMS, push) publicadas como eventos

```bash
axiz add notification
```

|  |  |
|---|---|
| Starter | `pe.axiz:axiz-notification-spring-boot-starter` |
| Stacks del catálogo | servlet, reactive |
| Agrega también | [`messaging-sns`](messaging-sns.md) |
| Incluida por defecto en | ningún arquetipo |

## Uso

```java
notificationSender.send(new NotificationRequest(id, NotificationChannel.EMAIL, to, "payment-confirmed", locale, params, null));
```

## Módulos

- [`axiz-notification-spring-boot-autoconfigure`](../referencia/spring/axiz-notification-spring-boot-autoconfigure.md)
- [`axiz-core-commons`](../referencia/core/axiz-core-commons.md)
- [`axiz-core-id`](../referencia/core/axiz-core-id.md)
- [`axiz-core-json`](../referencia/core/axiz-core-json.md)
- [`axiz-core-messaging`](../referencia/core/axiz-core-messaging.md)
- [`axiz-core-notification`](../referencia/core/axiz-core-notification.md)
