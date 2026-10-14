# EDA

Para servicios cuya entrada son eventos. Los eventos viajan como **CloudEvents 1.0** (modo binario
en Kafka, estructurado en SQS y SNS) y cada handler procesa un tipo.

```java
@Component
@HandlesEvent("pe.axiz.payments.payment-order.status-changed.v1")
public class PaymentNotificationHandler extends TypedEventHandler<PaymentOrderStatusChanged> {

  @Override
  protected void on(PaymentOrderStatusChanged event, EventEnvelope envelope) {
    // el payload ya viene decodificado; envelope trae id, source, correlación, tenant...
  }
}
```

En el stack reactivo el handler extiende `ReactiveTypedEventHandler` y devuelve un `Publisher`
que completa cuando el evento quedó procesado. El hilo del consumidor lo espera (acotado por
`axiz.eda.handler-timeout`), así el ack, los reintentos, la DLQ y el inbox funcionan igual; el
starter lo ejecuta dentro de una transacción reactiva con el contexto de la request y la traza del
mensaje, y `ReactiveReactions` encola reacciones en el outbox R2DBC en esa misma transacción.

```java
@Component
@HandlesEvent("pe.axiz.payments.payment-order.created.v1")
public class PaymentOrderCreatedHandler extends ReactiveTypedEventHandler<PaymentOrderCreated> {
  @Override
  protected Publisher<Void> on(PaymentOrderCreated event, EventEnvelope envelope) {
    return commandBus.send(new RecordPaymentRegisteredCommand(...));   // o repositorios R2DBC
  }
}
```

**Reglas** (`EdaRules`): los `EventHandler` viven en el paquete `handler` y el servicio no expone
controladores REST.

## Garantías

- **Consumo idempotente (inbox).** Cada mensaje se procesa una vez por consumidor dentro del
  periodo de retención: el dispatcher descarta duplicados consultando el inbox (JDBC, R2DBC o
  DynamoDB).
- **Transacción del handler.** Con un transaction manager, las escrituras del handler, el registro
  en el inbox y los eventos que encole en el outbox se confirman juntos o no se confirman.
- **Reintentos y DLQ.** Los fallos transitorios se reintentan con backoff hasta
  `axiz.messaging.consumer.max-attempts`; los permanentes (rechazo de negocio, payload inválido)
  van directo a la DLQ (`<topic>.dlq` en Kafka, la DLQ configurada en SQS).
- **Suscripciones verificadas.** `axiz.messaging.consumer.subscriptions` lista los tipos que deben
  tener handler; si falta uno, el servicio no arranca.

```yaml
axiz:
  messaging:
    type-prefix: pe.axiz.payments.
    consumer:
      max-attempts: 5
      subscriptions:
        - pe.axiz.payments.payment-order.status-changed.v1
    kafka:
      topics: [payments]
      group-id: notifications-processor
    sqs:
      queues: [https://sqs.us-east-1.amazonaws.com/111111111111/axiz-dev-audit]
    destinations:
      customer-notifications: arn:aws:sns:us-east-1:111111111111:axiz-dev-customer-notifications
```

## Coreografía

Para reaccionar a un evento emitiendo otro se usa `Reactions`: encola el nuevo evento en el outbox
dentro de la transacción del handler, con la correlación propagada y un id determinista derivado del
evento consumido, así una reentrega no duplica la reacción. `EventRoutes`
(`axiz.eda.routes`) decide el destino lógico de cada tipo.

En el demo, `notifications-processor` consume de Kafka (estados de órdenes) y de SQS (auditoría),
notifica por SNS y archiva la auditoría en S3 con locks en DynamoDB.
