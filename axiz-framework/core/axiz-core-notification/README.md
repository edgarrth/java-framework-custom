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
| `AsyncNotificationSender` | Native broker acknowledgement of a notification request, cancellable with its caller. |
| `AsyncEventNotificationSender` | Native event publication with the same id, correlation and wire schema as the imperative sender. |
| `EventNotificationSender` | Publishes notification requests as `notification.requested.v1` events to the delivering service (typically through SNS for fan-out or SQS). |
| `NotificationChannel` | Delivery channels. |
| `NotificationRequest` | Request to notify a recipient with a template. |
| `NotificationSender` | Port to request notifications. |
| `TemplateRenderer` | Renders `{{name`}} placeholders. |

## Delivery contract

Both senders share one event construction policy. Request ids remain event/deduplication ids;
correlation ids and template parameters keep their existing schema. Completion confirms broker
acceptance; the delivery service owns delivery to the recipient, provider retries and deduplication.
The asynchronous sender never invokes the imperative publisher or a worker offload.

## Tests

Unit tests live in `src/test/java` and document the expected behavior; run them with `./mvnw -pl core/axiz-core-notification verify`.
