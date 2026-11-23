<!-- GENERADO por scripts/sync_reference.py desde axiz-framework: no editar -->
# axiz-observability-spring-boot-autoconfigure

OpenTelemetry mediante Micrometer: spans del framework, eventos estructurados, protección de datos personales, grupos de salud y trazas del AWS SDK (autoconfiguración).

## Dependencia

```xml
<dependency>
  <groupId>pe.axiz</groupId>
  <artifactId>axiz-observability-spring-boot-autoconfigure</artifactId>
</dependency>
```

La versión la gestiona `axiz-bom` (los servicios heredan `axiz-parent-service`).

## Tipos principales

| Tipo | Propósito |
|---|---|
| `AxizObservabilityAutoConfiguration` | Observaciones del framework: spans en los puntos de arquitectura con atributos de negocio, eventos estructurados, atributos sin datos personales, contexto de la petición en los spans y sin observaciones para las sondas. |
| `AxizObservabilityEnvironmentPostProcessor` | Valores por defecto de OpenTelemetry (precedencia más baja): exportación OTLP de trazas y métricas al collector indicado por `OTEL_EXPORTER_OTLP_ENDPOINT`, sondas de liveness y readiness y atributos del recurso. |
| `AxizObservabilityProperties` | Convenciones de observabilidad (`axiz.observability.*`). |
| `IgnoredPathsObservationPredicate` | Omite las observaciones de servidor para rutas de infraestructura, como las sondas y el scraping de métricas. |
| `RequestContextObservationFilter` | Agrega el contexto de la petición a cada observación como atributos de alta cardinalidad: llegan a los spans, pero nunca se convierten en tags de métricas. |

## Pruebas

Las pruebas unitarias están en `src/test/java` y documentan el comportamiento esperado; se ejecutan con `./mvnw -pl spring/axiz-observability-spring-boot-autoconfigure verify`.
