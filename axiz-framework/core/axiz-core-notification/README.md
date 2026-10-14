<!-- generated -->
# axiz-core-notification

Notification requests (email, SMS, push) with templates, published as events.

## Dependency

```xml
<dependency>
  <groupId>pe.axiz</groupId>
  <artifactId>axiz-core-notification</artifactId>
</dependency>
```

The version is managed by `axiz-bom` (services inherit `axiz-parent-service`).

## Main types

| Type | Purpose |
|---|---|
| `EventNotificationSender` | Publishes notification requests as `notification.requested.v1` events to the delivering service (typically through SNS for fan-out or SQS). |
| `NotificationChannel` | Delivery channels. |
| `NotificationRequest` | Request to notify a recipient with a template. |
| `NotificationSender` | Port to request notifications. |
| `TemplateRenderer` | Renders `{{name`}} placeholders. |

## Tests

Unit tests live in `src/test/java` and document the expected behavior; run them with `./mvnw -pl core/axiz-core-notification verify`.
