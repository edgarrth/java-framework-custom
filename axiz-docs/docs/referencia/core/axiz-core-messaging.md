<!-- GENERADO por scripts/sync_reference.py desde axiz-framework: no editar -->
# axiz-core-messaging

EventPublisher y EventHandler sobre CloudEvents 1.0, cabeceras de negocio y políticas de reintento y DLQ.

## Dependencia

```xml
<dependency>
  <groupId>pe.axiz</groupId>
  <artifactId>axiz-core-messaging</artifactId>
</dependency>
```

La versión la gestiona `axiz-bom` (los servicios heredan `axiz-parent-service`).

## Tipos principales

| Tipo | Propósito |
|---|---|
| `CloudEventHeaders` | Mapeo del modo binario de CloudEvents a cabeceras o atributos del transporte con un prefijo (`ce_` para Kafka y atributos de SQS/SNS). |
| `CloudEventJson` | Formato JSON de CloudEvents (modo estructurado), usado donde los atributos del transporte son limitados (SQS, SNS) y para guardar en el outbox. |
| `ConsumerRetryPolicy` | Política de reintento del consumidor: los fallos transitorios se reintentan con backoff hasta `maxAttempts`; los permanentes (rechazos de negocio, payloads mal formados) van de inmediato a la DLQ. |
| `Deduplicator` | Puerto que usa el despachador para procesar cada mensaje una vez por consumidor (lo implementa el inbox). |
| `DeliveryDecision` | Lo que hace un consumidor con un mensaje después de procesarlo. |
| `EventDispatcher` | Enruta los sobres a los handlers por tipo, decodifica el payload y deduplica mediante el inbox. |
| `EventEnvelope` | Sobre CloudEvents 1.0. |
| `EventFactory` | Construye sobres con el origen del servicio, un prefijo de tipo y extensiones de contexto (correlación, tenant y cabeceras de negocio propagadas). |
| `EventHandler` | Atiende un tipo de evento. |
| `EventPublisher` | Publica eventos y espera la confirmación del broker. |
| `MessageKeys` | Identidad de un mensaje para la deduplicación. |
| `PayloadCodec` | Codifica y decodifica los payloads de eventos (JSON con el mapper del servicio en el starter). |
| `PayloadException` | El payload no pudo decodificarse: reintentar no ayuda. |
| `PublishException` | El broker no confirmó el evento: el fallo siempre es visible para el llamador. |
| `PublishResult` | Confirmación de un evento publicado. |
| `RoutingAsyncEventPublisher` | Selecciona el primer publicador nativo que soporta el destino; conserva su confirmación y cancelación, sin recurrir a otro broker tras un fallo. |
| `RoutingEventPublisher` | Delega en el primer publicador que soporta el destino. |

## Pruebas

Las pruebas unitarias están en `src/test/java` y documentan el comportamiento esperado; se ejecutan con `./mvnw -pl core/axiz-core-messaging verify`.
