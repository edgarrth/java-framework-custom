<!-- generated -->
# axiz-messaging-spring-boot-autoconfigure

CloudEvents publishers and consumers for MSK, SQS and SNS with retries, DLQ and inbox integration (auto-configuration).

## Dependency

```xml
<dependency>
  <groupId>pe.axiz</groupId>
  <artifactId>axiz-messaging-spring-boot-autoconfigure</artifactId>
</dependency>
```

The version is managed by `axiz-bom` (services inherit `axiz-parent-service`).

## Main types

| Type | Purpose |
|---|---|
| `ReactiveEvents` | Deferred native publication with subscriber metadata, broker acknowledgement and cancellation. |
| `ObservedAsyncEventPublisher` | Native producer spans, logical routing and continuation of stored outbox traces; scopes never cross the asynchronous wait. |
| `AsyncEventPublisherAdapter` | Named native broker adapter, separate from imperative poll/relay adapters. |
| `AxizNativeMessagingAutoConfiguration` | Reactive publisher/facade wiring and startup validation of configured destinations. |
| `AxizNativeMessagingAwsAutoConfiguration` | SQS/SNS native clients only for configured destinations or supplied clients; resources close with the context. |
| `AxizEvents` | Entry point for publishing: builds the CloudEvents envelope with the current request context and publishes it, confirming the broker acknowledgement or failing visibly. |
| `AxizMessagingAutoConfiguration` | CloudEvents messaging: envelope factory, JSON codec, observed publisher routed to the broker adapters, and the dispatcher over all `EventHandler` beans with retries, dead-lettering and de-duplication (inbox starter). |
| `AxizMessagingAwsAutoConfiguration` | Amazon SQS and SNS publishers (FIFO deduplication id = event id) and SQS consumers. |
| `AxizMessagingKafkaAutoConfiguration` | Amazon MSK: IAM-authenticated idempotent producer for `kafka:` destinations, and listener containers for `axiz.messaging.kafka.topics` with exponential redelivery and a dead-letter topic. |
| `AxizMessagingProperties` | Messaging (`axiz.messaging.*`): CloudEvents 1.0 envelopes over MSK, SQS and SNS. |
| `EventPublisherAdapter` | A broker publisher contributed by an adapter auto-configuration (MSK, SQS, SNS). |
| `JacksonPayloadCodec` | Copies the application's Jackson mapper and modules for event payloads. Accepts additive fields and preserves decimals in untyped nested maps/lists as `BigDecimal`; the HTTP mapper keeps its own configuration. |
| `KafkaConsumers` | Starts the listener containers after the context is ready and stops them on shutdown. |
| `ObservedDispatcher` | Runs the dispatcher inside a CONSUMER observation continuing the producer's trace, with the request context (correlation id, tenant, channel) restored from the envelope. |
| `ObservedEventPublisher` | Publishes inside a PRODUCER observation. |
| `SqsEventConsumer` | Long-polling SQS consumer, one virtual thread per queue. |

## Reactive producers

Reactive flows use `ReactiveEvents` or `AsyncEventPublisher`. Logical destinations resolve through
`axiz.messaging.destinations`, and routing selects the first matching native adapter. A failed send
never triggers another broker. Destinations without a native adapter fail startup; Kafka reactive publication uses the bounded submission boundary described below.

AWS resources are created at startup only when needed; supplied clients are borrowed. Owned event
loops, credentials and bounded completion resources close with their owning context. The global
`axiz.messaging.enabled=false` switch disables native wiring as well. Imperative publishers remain
available to poll/relay workers at the permitted lifecycle boundary, never as a reactive fallback.

Subscriber correlation/tenant metadata is read from Reactor Context. Producer spans inject W3C
extensions before publication; stored outbox trace extensions create a continuation span and are
removed before the event reaches the broker. Local context scopes preserve parents across SDK
callbacks without global Reactor hooks or held scopes between threads.

## Tests

Unit tests live in `src/test/java` and document the expected behavior; run them with `./mvnw -pl spring/axiz-messaging-spring-boot-autoconfigure verify`.

### Kafka submission in reactive applications

Servlet publication keeps its synchronous adapter. In reactive applications the framework invokes
`KafkaTemplate.send()` on dedicated workers because metadata lookup or a full producer buffer can
wait inside `send()`. Completion means broker acknowledgement; there is no wait for that ack on a
worker or Reactor thread. The standard Kafka client is retained.

`axiz.messaging.kafka.send-workers` defaults to 2 and `send-queue-capacity` to 256. Admission is
bounded across queued work and unacknowledged sends; exhaustion fails immediately rather than
running on the caller. The existing Kafka timeout bounds submission plus acknowledgement and
`max.block.ms`. Metrics `axiz.transport.pending`, `.queued`, `.rejected`, and `.latency` carry the
`transport` tag (`kafka-events` or `kafka-audit`). Producers and workers close with the context.

Cancelling queued work prevents its send. Once Kafka has accepted a send, cancellation or timeout
cannot retract it: a late acknowledgement is possible, and capacity remains reserved until the
transport completes. Retrying must preserve the event ID and use consumer inbox deduplication.
