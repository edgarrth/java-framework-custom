# Observabilidad

Trazas y métricas con **OpenTelemetry a través de Micrometer** (OTLP), sin agente de Java
([capas y ejecución](../arquitectura/capas.md)); logs JSON correlacionados con la traza. En
EKS los servicios envían al OpenTelemetry Collector; las métricas terminan en Prometheus, los
dashboards en Grafana y las trazas y logs en el backend OTLP de plataforma. En local, todo va al
contenedor `otel-lgtm` (Grafana, Prometheus, Tempo y Loki).

## Qué se instrumenta

| Punto | Observación | Atributos de baja cardinalidad |
|---|---|---|
| HTTP de entrada | `http.server.requests` | `method`, `uri`, `status`, `outcome` |
| Clientes HTTP (`RestClient` y `WebClient`) | `http.client.requests` | `client.name` (nombre lógico), `status`, `outcome` |
| Clientes SOAP | `axiz.soap.client` | `axiz.client`, `axiz.operation` |
| AWS SDK v2 | `axiz.aws.sdk` | `aws.service`, `aws.operation`, `outcome` |
| Casos de uso | `axiz.usecase` | `axiz.operation`, `axiz.outcome` |
| Commands y queries | `axiz.command`, `axiz.query` | `axiz.operation`, `axiz.outcome` |
| Handlers de eventos | `axiz.event.handler` | `axiz.event.type` |
| Publicación de eventos | `axiz.event.publish` | `axiz.destination`, `axiz.event.type` |
| Escritura en el outbox | `axiz.outbox.write` | `axiz.destination`, `axiz.event.type` |
| Relay del outbox (lote) | `axiz.outbox.relay` | Ninguno |
| Publicación de un evento del outbox | `axiz.outbox.dispatch` | `axiz.destination`, `axiz.event.type` |
| Pasos de batch | `axiz.batch.step` | Ninguno |

Además: `axiz.outbox.pending` (registros esperando), `axiz.saga.steps`, `axiz.saga.compensations`
y `axiz.saga.finished`, las [métricas por evento](../guias/metricas.md) y las de Spring Boot (JVM,
pools, Kafka, Resilience4j, Lettuce). Todas llevan `service`, `env`, `version` y `domain`.

Los ids (correlación, evento, agregado, request de AWS) van como atributos de **alta** cardinalidad:
están en las trazas, nunca en las métricas. `AttributeSanitizer` elimina llaves denegadas y
redacta valores con forma de tarjeta o correo antes de exportarlos.

## Contexto y correlación

El header de correlación (o el `traceparent`) se valida, se genera si falta y viaja en el MDC (o el
Reactor Context), en los headers de las llamadas salientes y en las extensiones de los CloudEvents;
cada línea de log lleva `traceId`, `spanId`, `correlationId` y el tenant. Los eventos llevan la
correlación de la request que los originó (también los que salen por el outbox) y la extensión
`traceparent` de su publicación, así el consumidor continúa esa traza.

Un evento que pasa por el outbox se publica después de la request, en el hilo del relay. Para que
la traza no se corte, `OutboxWriter` registra la escritura en una observación `axiz.outbox.write`
(hija de la request) y guarda su contexto con el evento, en la extensión `outboxtraceparent`. Al
publicarlo, el relay abre `axiz.outbox.dispatch`, que continúa ese contexto, y quita la extensión
antes de enviarlo al broker. El resultado es una sola traza: request -> `outbox write` -> `outbox
dispatch` -> `publish` -> handler del consumidor -> sus llamadas. Vale para todo lo que usa el outbox
(eventos de dominio, reacciones de EDA, auditoría durable). Requiere propagación W3C, la que Spring
Boot usa por defecto; con B3 la traza se corta en el outbox como antes.

Las llamadas HTTP salientes de los clientes de `axiz.http-client` se observan con el
`ObservationRegistry` de la aplicación en los dos stacks: el span del cliente es hijo del span
actual y su contexto viaja en `traceparent`. En Spring Boot 4 el soporte de `WebClient` es un
módulo aparte (`spring-boot-starter-webclient`), que los servicios reactivos con clientes HTTP
deben declarar; sin él las llamadas igual se observan, pero con el `WebClient` sin la
configuración de Boot (codecs). El log de cada request de entrada incluye el `traceparent`
recibido, así se ve en qué salto se cortó una traza.

## Configuración

| Propiedad | Por defecto |
|---|---|
| `management.opentelemetry.tracing.export.otlp.endpoint` | `${OTEL_EXPORTER_OTLP_TRACES_ENDPOINT}` |
| `management.otlp.metrics.export.url` | `${OTEL_EXPORTER_OTLP_METRICS_ENDPOINT}` |
| `management.tracing.sampling.probability` | `${AXIZ_TRACE_SAMPLING:1.0}` (en el ConfigMap de cada ambiente) |
| `management.endpoints.web.exposure.include` | `health,info,prometheus` |

En Spring Boot 4 la propiedad del endpoint de trazas es
`management.opentelemetry.tracing.export.otlp.endpoint`; la de Boot 3,
`management.otlp.tracing.endpoint`, se ignora (Boot la marca como eliminada).

La exportación de métricas OTLP (`micrometer-registry-otlp`) usa `protobuf-java`. Hasta la 4.35 su
runtime llama a métodos de `sun.misc.Unsafe` marcados para eliminación (Java 24+ avisa al
arrancar); `axiz-dependencies` fija la 4.36.2, la primera cuyo runtime completo (el que usa el
código generado de OpenTelemetry) no los usa.

## Dashboards base

`axiz-docs/dashboards` publica los dashboards de la plataforma (generados por
`scripts/dashboards.py`); plataforma los provisiona en la carpeta *axiz* de Grafana y el demo los
monta en su Grafana local.

| Dashboard | Variables | Qué muestra |
|---|---|---|
| `axiz-events`: eventos | `$env`, `$service`, `$event`, `$channel`, `$product` | volumen, ratio de éxito, motivos de rechazo, montos (total, ticket promedio y p95), duración, valores, niveles, procesos en curso, embudo por etapa |
| `axiz-red`: servicios | `$env`, `$service`, `$uri` | tasa, errores 5xx y latencia HTTP; casos de uso, commands y queries por resultado; CPU, heap, threads, pool JDBC, 429 y circuit breakers |
| `axiz-messaging`: mensajería | `$env`, `$service`, `$type` | eventos procesados y fallidos, duración de handlers, lag de Kafka, publicación por destino, outbox pendiente y relay, sagas y compensaciones |
| `axiz-dependencies`: dependencias | `$env`, `$service`, `$client` | llamadas, latencia y errores por cliente HTTP, circuit breakers, SOAP, AWS por servicio y operación, espera de conexiones JDBC, Redis |

## Alertas sugeridas

- Ratio de 5xx de un servicio > 2 % durante 10 minutos.
- `axiz_outbox_pending` creciendo durante 15 minutos (broker o relay caídos).
- Lag de un consumidor creciendo durante 15 minutos o mensajes en una DLQ (CloudWatch para SQS).
- Compensaciones de una saga por encima de su línea base.
- `axiz_saga_in_doubt_total` > 0: un paso terminó con resultado incierto (timeout, respuesta
  perdida) y no se pudo confirmar. La saga queda en `IN_DOUBT` y la recuperación la vuelve a
  verificar. Si no se resuelve, requiere conciliación manual.
- `axiz_audit_lost_total` > 0, o `axiz_audit_worker_alive` = 0 durante 5 minutos: hay eventos de
  auditoría perdidos o el publicador asíncrono está detenido.
- Error de arranque por esquema del inbox desactualizado (`Inbox table ... is missing or outdated`).
- Circuit breaker abierto más de 5 minutos.
- Job batch que termina con código distinto de cero (estado del CronJob).

## Imagen nativa

Con GraalVM la instrumentación por librería y los spans del framework funcionan igual (no hay
agente); los hints de AOT los generan los starters.
