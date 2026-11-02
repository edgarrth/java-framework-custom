# EDA

La estructura EDA organiza reacciones y coreografía. Los handlers pertenecen a messaging y
también pueden usarse con hexagonal. Para servicios cuya entrada son eventos, Los eventos viajan como **CloudEvents 1.0** (modo binario
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
`axiz.messaging.consumer.handler-timeout`), así el ack, los reintentos, la DLQ y el inbox funcionan igual; el
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

Como no expone API, el starter no incluye el resource server de seguridad (no hay tokens que
validar). El rol `event-processor` no expone REST de negocio; un servicio que necesite
REST y eventos elige una combinación del catálogo que permita ambas entradas.

## Garantías

- **Deduplicación con lease (inbox).** Se descartan mensajes ya registrados y se difieren entregas
  concurrentes. La marca de completado es posterior a la transacción del handler: una caída entre
  commit y completado permite reentregas. El handler debe ser idempotente.
- **Transacción del handler.** Con un transaction manager, las escrituras del handler y sus eventos
  en el outbox se confirman o revierten juntos. La marca del inbox se realiza por separado.
- **Reintentos y DLQ.** Los fallos transitorios se reintentan con backoff hasta
  `axiz.messaging.consumer.max-attempts`; los permanentes (rechazo de negocio, payload inválido)
  van directo a la DLQ (`<topic>.dlq` en Kafka, la DLQ configurada en SQS).
- **Suscripciones verificadas.** `axiz.messaging.consumer.subscriptions` lista los tipos que deben
  tener handler; si falta uno, el servicio no arranca.
- **Tipos sin handler.** Por defecto un evento sin handler va a la DLQ (un tipo inesperado es un
  error de contrato). Si el topic es compartido y lleva tipos que el servicio no procesa (el topic
  `payments` del demo lleva `created` y `status-changed`), `axiz.messaging.consumer.ignore-unknown-types:
  true` los confirma sin procesarlos.
- **Consumidores.** Se crea un listener por cada topic de `axiz.messaging.kafka.topics` (y un
  consumidor por cada cola de `axiz.messaging.sqs.queues`), escritos como lista YAML, con índices
  (`topics[0]=...`) o separados por comas. Al arrancar, Kafka registra `ConsumerConfig values` y
  luego `partitions assigned` para el `group-id`; si no aparecen, el servicio no está consumiendo.

```yaml
axiz:
  messaging:
    type-prefix: pe.axiz.payments.
    consumer:
      max-attempts: 5
      ignore-unknown-types: true
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
(`axiz.messaging.routes.by-type`) decide el destino lógico de cada tipo.

El outbox necesita una base de datos. El starter trae su configuración, pero no el pool de
conexiones: lo aportan la capacidad `outbox` o la persistencia Aurora (JDBC o R2DBC). Un
procesador sobre DynamoDB, o sin persistencia, arranca sin `DataSource`; en ese caso `Reactions`
no está disponible.

En el demo, `notifications-processor` consume de Kafka (estados de órdenes) y de SQS (auditoría),
notifica por SNS y archiva la auditoría en S3 con locks en DynamoDB.
