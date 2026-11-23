<!-- generated -->
# axiz-adapter-audit-kafka

Publicador de auditoría para Amazon MSK (Kafka).

## Dependencia

```xml
<dependency>
  <groupId>pe.axiz</groupId>
  <artifactId>axiz-adapter-audit-kafka</artifactId>
</dependency>
```

La versión la gestiona `axiz-bom` (los servicios heredan `axiz-parent-service`).

## Tipos principales

| Tipo | Propósito |
|---|---|
| `KafkaAuditRecords` | Codificación CloudEvents binaria compartida para la publicación síncrona y asíncrona en Kafka. |
| `KafkaAuditPublisher` | Publica eventos de auditoría en un tópico de Amazon MSK (Kafka). |

## Pruebas

Las pruebas unitarias están en `src/test/java` y documentan el comportamiento esperado; se ejecutan con `./mvnw -pl adapters/axiz-adapter-audit-kafka verify`.
