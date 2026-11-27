<!-- generated -->
# axiz-adapter-messaging-sqs

Adaptador publicador y consumidor de Amazon SQS (estándar y FIFO).

## Dependencia

```xml
<dependency>
  <groupId>pe.axiz</groupId>
  <artifactId>axiz-adapter-messaging-sqs</artifactId>
</dependency>
```

La versión la gestiona `axiz-bom` (los servicios heredan `axiz-parent-service`).

## Tipos principales

| Tipo | Propósito |
|---|---|
| `SqsEnvelopes` | Convierte los mensajes SQS (incluidos los mensajes crudos entregados por SNS) en sobres. |
| `SqsEventPublisher` | Publica CloudEvents (JSON estructurado) en Amazon SQS. |

## Publicación nativa

`SqsAsyncEventPublisher` usa el cliente asíncrono del SDK y comparte la construcción de
peticiones con el adaptador imperativo. Completar con éxito equivale a la confirmación del broker.
La cancelación llega al futuro exacto del SDK; los errores de envío se devuelven como etapas
fallidas, y las cancelaciones y fallos fatales conservan su significado. Las identidades FIFO de
grupo y de deduplicación son las mismas.

El enrutamiento SQS reconoce las URLs de AWS y las URLs HTTP de colas de emuladores, y rechaza
direcciones mal formadas, credenciales en la URL, parámetros de consulta y fragmentos.

## Pruebas

Las pruebas unitarias están en `src/test/java` y documentan el comportamiento esperado; se ejecutan con `./mvnw -pl adapters/axiz-adapter-messaging-sqs verify`.
