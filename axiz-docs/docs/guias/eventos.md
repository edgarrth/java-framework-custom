# Eventos, outbox e inbox

## Formato

Todos los eventos son **CloudEvents 1.0**: `id`, `source` (`urn:axiz:<servicio>`), `type`
(`<prefijo><agregado>.<hecho>.v<n>`, por ejemplo `pe.axiz.payments.payment-order.created.v1`),
`subject` (id del agregado, también llave de partición), `time` y extensiones de contexto
(correlación, tenant y headers de negocio propagados). En Kafka viajan en **modo binario** (headers
`ce_*`), en SQS y SNS en **modo estructurado** (JSON). Se describen en un contrato **AsyncAPI 3**.

Los destinos son **lógicos** y se resuelven por configuración:

```yaml
axiz:
  messaging:
    type-prefix: pe.axiz.payments.
    destinations:
      payments: kafka:payments
      customer-notifications: arn:aws:sns:us-east-1:111111111111:axiz-dev-customer-notifications
      audit: https://sqs.us-east-1.amazonaws.com/111111111111/axiz-dev-audit
```

## Publicar

Directo (espera la confirmación del broker; un fallo siempre llega al llamador):

```java
axizEvents.publish("payments", "payment-order.created.v1", order.id(), payload);
```

## Outbox: publicar si y solo si el cambio se confirma

Cuando un evento acompaña un cambio de datos, se escribe en la tabla outbox **en la misma
transacción** y un relay lo publica después (al menos una vez):

```java
transactionalEvents.enqueue(
    "payments",                     // destino lógico
    null,                           // id del evento de dominio (null: se genera)
    "payment-order.created.v1",     // tipo sin prefijo
    "payment-order",                // tipo de agregado
    order.id(),                     // subject y llave de partición
    new PaymentOrderCreated(...));
```

- Fuera de una transacción falla de inmediato (no hay "outbox sin transacción").
- El relay toma lotes con `FOR UPDATE SKIP LOCKED` (varias réplicas sin pisarse), reintenta con
  backoff hasta `axiz.outbox.relay.max-attempts`, marca como fallidos los que no salen y purga los
  publicados tras `retention`.
- La traza no se corta: el evento guarda el contexto de la escritura y el relay lo continúa al
  publicar, así la request, la publicación y los consumidores quedan en una sola traza (ver
  [Observabilidad](../operacion/observabilidad.md#contexto-y-correlacion)).
- En hexagonal, los eventos de dominio registrados por el agregado van al outbox automáticamente
  con la capacidad `outbox` (ver [Hexagonal](../estructuras/hexagonal.md)).
- En SQS FIFO el id del evento es el id de deduplicación.
- En servicios reactivos sobre Aurora (R2DBC) se usa `ReactiveTransactionalEvents`: `enqueue(...)`
  devuelve un `Mono` que inserta en el outbox con la misma conexión y transacción reactiva del
  cambio de negocio, y falla si no hay transacción. El relay es el mismo (en un virtual thread,
  con sus propias conexiones R2DBC) y la métrica `axiz.outbox.pending` se refresca fuera del
  hilo del scrape.

### Garantías y orden

- **Al menos una vez.** Un evento puede publicarse más de una vez (caída después de publicar y antes
  de marcarlo); los consumidores deduplican con el inbox.
- **Sin orden global ni por agregado entre relays.** `ORDER BY created_at` con `SKIP LOCKED` ordena
  dentro de un lote, pero dos réplicas toman lotes distintos en paralelo, y el backoff de un evento
  fallido deja salir a los posteriores antes. Si un contrato necesita orden estricto por agregado:
  publica con el id del agregado como llave de partición (Kafka y SQS FIFO conservan el orden por
  llave), lleva una versión o secuencia por agregado en el evento y haz que el consumidor detecte
  versiones atrasadas o huecos y los descarte o espere.
- **Transacción abierta mientras se publica.** El relay mantiene los locks del lote mientras envía
  al broker: mantén `axiz.outbox.relay.batch-size` chico y vigila la duración de la transacción,
  la edad del evento más antiguo y `axiz.outbox.pending`. No muevas la publicación fuera de la
  transacción sin diseñar antes la recuperación de reservas vencidas.

El outbox persiste los eventos dentro de la transacción de negocio y los publica con reintentos desde un worker independiente.

## Consumir

Ver el estilo [EDA](../estructuras/eda.md): handlers tipados con `@HandlesEvent`, suscripciones
verificadas al arrancar, reintentos con backoff, DLQ para fallos permanentes. Un servicio de otro
estilo también puede consumir eventos con handlers en `adapter/in/messaging` (en el demo,
`payment-process` arranca su saga desde `PaymentOrderCreatedHandler`).

## Inbox: procesar una sola vez

La entrega es al menos una vez, así que cada consumidor descarta duplicados con el **inbox**
(`axiz add inbox`): registra el id del mensaje por consumidor con una concesión (`lease`) mientras
se procesa y lo recuerda durante `retention` (TTL en DynamoDB, purga en JDBC y R2DBC; en
servicios reactivos sobre Aurora, `axiz.inbox.store: r2dbc`). Con transacción, el
registro en el inbox y las escrituras del handler se confirman juntos.

Un mensaje se identifica por **source + id** (la identidad de CloudEvents): el mismo id enviado por
dos productores son dos mensajes distintos, y la reentrega del mismo evento es un duplicado. La
llave del inbox combina ambos (`MessageKeys`).

Cada entrega toma el mensaje con un **token de dueño**:

| Situación | Resultado |
|---|---|
| mensaje nuevo, o concesión vencida de una entrega que murió | se procesa |
| ya procesado | se confirma (ACK) sin ejecutar el handler |
| otra entrega lo está procesando (concesión vigente) | **no** se confirma: Kafka reintenta tras `axiz.messaging.consumer.defer-interval` sin consumir los reintentos del mensaje; SQS lo devuelve a la cola hasta que vence la concesión |
| el inbox no responde | se trata como fallo transitorio (reintento), nunca como duplicado |

Marcar el mensaje como procesado o liberarlo solo funciona para la entrega que lo tiene: una entrega
tardía cuya concesión ya tomó otra no puede tocar el registro nuevo.

En SQS el consumidor extiende la visibilidad de los mensajes en curso cada mitad de
`axiz.messaging.sqs.visibility-timeout`, así un handler lento no provoca una segunda
entrega en paralelo; solo los mensajes que no se pueden decodificar van directo a la DLQ, el resto
de los fallos sigue la política de reintentos. Al detenerse, espera a los handlers en curso hasta
30 s antes de interrumpirlos.

La tabla JDBC/R2DBC del inbox tiene la columna `owner`: si falta, el servicio no arranca (antes
de consumir) y el mensaje indica la migración a agregar.

El inbox protege el registro de consumo, no el efecto: una entrega cuya concesión venció puede
seguir ejecutándose mientras otra la toma. Un efecto de negocio que no puede repetirse (un débito,
una transferencia) necesita además su propia unicidad durable en el sistema que lo produce (ver
[Idempotencia](idempotencia.md#lo-que-la-idempotencia-http-no-cubre)).

## Notificaciones

`NotificationSender` publica `notification.requested.v1` hacia el servicio que entrega (SNS para
fan-out o SQS) con canal (`EMAIL`, `SMS`, `PUSH`...), destinatario, plantilla, idioma y parámetros;
el servicio de canal renderiza con `TemplateRenderer`.

## Límites de PostgreSQL y conexiones abandonadas

Para los relays configura límites de conexión, consulta y espera de locks compatibles con su carga.
`idle_in_transaction_session_timeout` acota transacciones abandonadas inactivas; PostgreSQL 17+
también permite `transaction_timeout` para limitar su duración total. Usa configuración de rol o
sesión apropiada: si el rol comparte transacciones del negocio, los valores deben respetarlas.
Axiz no cambia parámetros globales del servidor. Consulta los
[parámetros de PostgreSQL 17](https://www.postgresql.org/docs/17/runtime-config-client.html).

Estas protecciones complementan el cierre y rollback del cliente. `SKIP LOCKED` no evita envíos
duplicados; la entrega del outbox sigue siendo at-least-once y el consumidor debe ser idempotente.
