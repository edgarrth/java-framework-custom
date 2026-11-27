<!-- GENERADO por scripts/sync_reference.py desde axiz-framework: no editar -->
# axiz-core-metrics

Métricas genéricas de eventos: siete instrumentos parametrizados por el tag del evento con protección de cardinalidad.

## Dependencia

```xml
<dependency>
  <groupId>pe.axiz</groupId>
  <artifactId>axiz-core-metrics</artifactId>
</dependency>
```

La versión la gestiona `axiz-bom` (los servicios heredan `axiz-parent-service`).

## Tipos principales

| Tipo | Propósito |
|---|---|
| `EventDefinition` | Entrada opcional del catálogo para un evento: fija su categoría, la unidad de su valor y los buckets de histograma (SLO) de sus distribuciones. |
| `EventMetrics` | Métricas genéricas de eventos: siete instrumentos parametrizados por el tag `event`. |
| `EventMetricsMeterFilter` | Filtro de meters para los instrumentos `axiz.events*`: rechaza meters con claves de tag no declaradas (defensa en profundidad contra tags improvisados) y aplica los buckets de histograma del catálogo de eventos. |
| `FailureClassifier` | Deriva el resultado y el motivo de una operación fallida. |
| `Metered` | Cuenta y mide el tiempo de un método como evento. |
| `MetricEvent` | Descripción de una ocurrencia de un evento. |
| `MetricNames` | Nombres de los siete instrumentos genéricos (estilo OpenTelemetry; Prometheus agrega sufijos). |
| `MetricSource` | Lo implementan los resultados (o comandos) que aportan a la métrica que registra `Metered`: importe, valor y tags. |
| `MetricTags` | Claves de tag estándar de los instrumentos genéricos de eventos. |
| `MetricsSettings` | Configuración de las métricas genéricas de eventos (enlazada desde `axiz.metrics.*`). |
| `Outcome` | Valores estándar de la etiqueta de resultado. |
| `TagPolicy` | Política de un tag: un conjunto explícito de valores permitidos o, si está vacío, un máximo de valores distintos. |

## Pruebas

Las pruebas unitarias están en `src/test/java` y documentan el comportamiento esperado; se ejecutan con `./mvnw -pl core/axiz-core-metrics verify`.
