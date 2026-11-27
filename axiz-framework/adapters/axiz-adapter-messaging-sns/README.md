<!-- generated -->
# axiz-adapter-messaging-sns

Adaptador publicador de Amazon SNS (estándar y FIFO).

## Dependencia

```xml
<dependency>
  <groupId>pe.axiz</groupId>
  <artifactId>axiz-adapter-messaging-sns</artifactId>
</dependency>
```

La versión la gestiona `axiz-bom` (los servicios heredan `axiz-parent-service`).

## Tipos principales

| Tipo | Propósito |
|---|---|
| `SnsEventPublisher` | Publica CloudEvents (JSON estructurado) en tópicos de Amazon SNS (difusión). |

## Publicación nativa

`SnsAsyncEventPublisher` usa el cliente asíncrono del SDK y comparte la construcción de
peticiones con el adaptador imperativo. Completar con éxito equivale a la confirmación del broker.
La cancelación llega al futuro exacto del SDK; los errores de envío se devuelven como etapas
fallidas, y las cancelaciones y fallos fatales conservan su significado. Las identidades FIFO de
grupo y de deduplicación son las mismas.

## Pruebas

Las pruebas unitarias están en `src/test/java` y documentan el comportamiento esperado; se ejecutan con `./mvnw -pl adapters/axiz-adapter-messaging-sns verify`.
