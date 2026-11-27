<!-- generated -->
# axiz-messaging-spring-boot-autoconfigure

Los publicadores exponen el presupuesto visible para el llamador mediante `BoundedPublisher`. Kafka usa la
misma configuración del productor para la construcción y para su declaración `max.block.ms + sendTimeout`.
SQS y SNS leen `apiCallTimeout` del cliente SDK efectivo; los clientes personalizados sin un timeout
legible quedan como desconocidos en lugar de heredar un valor por defecto supuesto. Los clientes AWS de
mensajería creados por Axiz usan `axiz.messaging.aws.api-call-timeout=10s` (positivo). El long-polling de
recepción de SQS tiene un timeout por petición separado que cubre su tiempo de espera, de modo que un
timeout de envío corto no rompe a los consumidores.

Publicadores y consumidores CloudEvents para MSK, SQS y SNS con reintentos, DLQ e integración con inbox (autoconfiguración).

## Dependencia

```xml
<dependency>
  <groupId>pe.axiz</groupId>
  <artifactId>axiz-messaging-spring-boot-autoconfigure</artifactId>
</dependency>
```

La versión la gestiona `axiz-bom` (los servicios heredan `axiz-parent-service`).

## Tipos principales

| Tipo | Propósito |
|---|---|
| `ReactiveEvents` | Publicación nativa diferida con metadatos del suscriptor, confirmación del broker y cancelación. |
| `ObservedAsyncEventPublisher` | Spans nativos del productor, enrutamiento lógico y continuación de las trazas guardadas en el outbox; los scopes nunca cruzan la espera asíncrona. |
| `AsyncEventPublisherAdapter` | Adaptador nativo de broker con nombre, separado de los adaptadores imperativos de poll y relay. |
| `AxizNativeMessagingAutoConfiguration` | Cableado del publicador y la fachada reactivos y validación al arranque de los destinos configurados. |
| `AxizEvents` | Punto de entrada para publicar: construye el sobre CloudEvents con el contexto de la petición actual y lo publica, confirmando la recepción del broker o fallando de forma visible. |
| `AxizMessagingAutoConfiguration` | Mensajería CloudEvents: fábrica de sobres, codec JSON, publicador observado enrutado a los adaptadores de broker y el despachador sobre todos los beans `EventHandler` con reintentos, dead-lettering y deduplicación (starter de inbox). |
| `AxizMessagingProperties` | Mensajería (`axiz.messaging.*`): sobres CloudEvents 1.0 sobre MSK, SQS y SNS. |
| `EventPublisherAdapter` | Publicador de broker aportado por la autoconfiguración de un adaptador (MSK, SQS, SNS). |
| `JacksonPayloadCodec` | Copia el mapper Jackson de la aplicación y sus módulos para los payloads de eventos. Acepta campos adicionales y conserva los decimales en mapas y listas anidados sin tipo como `BigDecimal`; el mapper HTTP mantiene su propia configuración. |
| `ObservedDispatcher` | Ejecuta el despachador dentro de una observación CONSUMER que continúa la traza del productor, con el contexto de la petición (id de correlación, tenant, canal) restaurado desde el sobre. |
| `ObservedEventPublisher` | Publica dentro de una observación PRODUCER. |
| `InboxCompletion` | Puerto que implementan los almacenes de inbox para que un handler de eventos transaccional complete su entrada de inbox en la transacción de negocio (JDBC). |
| `ReactiveInboxCompletion` | El mismo puerto para transacciones reactivas (R2DBC). |

Tipos relacionados: `AxizMessagingKafkaAutoConfiguration`, `KafkaConsumers` están en `axiz-messaging-kafka-spring-boot-autoconfigure`; `SqsEventConsumer` están en `axiz-messaging-sqs-spring-boot-autoconfigure`.

## Productores reactivos

Los flujos reactivos usan `ReactiveEvents` o `AsyncEventPublisher`. Los destinos lógicos se resuelven
mediante `axiz.messaging.destinations` y el enrutamiento selecciona el primer adaptador nativo que
coincide. Un envío fallido nunca activa otro broker. Los destinos sin adaptador nativo hacen fallar el arranque; la publicación reactiva en Kafka usa el límite de envío acotado que se describe más abajo.

Los recursos AWS se crean al arranque solo cuando hacen falta; los clientes provistos se toman prestados.
Los event loops propios, las credenciales y los recursos de finalización acotados se cierran con el
contexto dueño. El interruptor global `axiz.messaging.enabled=false` también desactiva el cableado
nativo. Los publicadores imperativos siguen disponibles para los workers de poll y relay en el límite de
ciclo de vida permitido, nunca como respaldo reactivo.

Los metadatos de correlación y tenant del suscriptor se leen del Context de Reactor. Los spans del
productor inyectan las extensiones W3C antes de la publicación; las extensiones de traza guardadas en el
outbox crean un span de continuación y se eliminan antes de que el evento llegue al broker. Los scopes de
contexto locales conservan a los padres a lo largo de los callbacks del SDK, sin hooks globales de Reactor
ni scopes retenidos entre hilos.

## Pruebas

Las pruebas unitarias están en `src/test/java` y documentan el comportamiento esperado; se ejecutan con `./mvnw -pl spring/axiz-messaging-spring-boot-autoconfigure verify`.

### Envío a Kafka en aplicaciones reactivas

La publicación servlet conserva su adaptador síncrono. En aplicaciones reactivas el framework invoca
`KafkaTemplate.send()` en workers dedicados, porque la búsqueda de metadatos o un buffer del productor
lleno pueden esperar dentro de `send()`. Completar significa confirmación del broker; esa confirmación no
se espera en un worker ni en un hilo de Reactor. Se mantiene el cliente Kafka estándar.

`axiz.messaging.kafka.send-workers` vale 2 por defecto y `send-queue-capacity`, 256. La admisión se
acota entre el trabajo encolado y los envíos sin confirmar; al agotarse falla de inmediato en lugar de
ejecutarse en el llamador. El timeout de Kafka configurado acota el envío más la confirmación y
`max.block.ms`. Las métricas `axiz.transport.pending`, `.queued`, `.rejected` y `.latency` llevan el tag
`transport` (`kafka-events` o `kafka-audit`). Los productores y workers se cierran con el contexto.

Cancelar trabajo encolado impide su envío. Una vez que Kafka aceptó un envío, la cancelación o el
timeout no pueden retirarlo: es posible una confirmación tardía, y la capacidad sigue reservada hasta que
el transporte termina. Un reintento debe conservar el ID del evento y usar la deduplicación del inbox del consumidor.
