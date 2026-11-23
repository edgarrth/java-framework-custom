<!-- GENERADO por scripts/sync_reference.py desde axiz-framework: no editar -->
# axiz-adapter-audit-sqs

Publicador de auditoría para Amazon SQS.

## Dependencia

```xml
<dependency>
  <groupId>pe.axiz</groupId>
  <artifactId>axiz-adapter-audit-sqs</artifactId>
</dependency>
```

La versión la gestiona `axiz-bom` (los servicios heredan `axiz-parent-service`).

## Tipos principales

| Tipo | Propósito |
|---|---|
| `SqsAsyncAuditPublisher` | Aceptación nativa del SDK con una cancelación que llega al futuro exacto del SDK; toma prestado su cliente. |
| `SqsAuditPublisher` | Publica eventos de auditoría en una cola de Amazon SQS. |

Ambas rutas comparten la codificación CloudEvents y la construcción de peticiones FIFO. El
identificador del evento es el de deduplicación y el identificador de grupo es el tipo e
identificador del recurso, de modo que productores imperativos y nativos conviven en la misma cola.

## Pruebas

Las pruebas unitarias están en `src/test/java` y documentan el comportamiento esperado; se ejecutan con `./mvnw -pl adapters/axiz-adapter-audit-sqs verify`.
