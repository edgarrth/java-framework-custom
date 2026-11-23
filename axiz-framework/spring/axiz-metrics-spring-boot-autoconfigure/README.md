<!-- generated -->
# axiz-metrics-spring-boot-autoconfigure

Métricas genéricas de eventos con @Metered, catálogo, tags comunes y protección de cardinalidad (autoconfiguración).

## Dependencia

```xml
<dependency>
  <groupId>pe.axiz</groupId>
  <artifactId>axiz-metrics-spring-boot-autoconfigure</artifactId>
</dependency>
```

La versión la gestiona `axiz-bom` (los servicios heredan `axiz-parent-service`).

## Tipos principales

| Tipo | Propósito |
|---|---|
| `AxizFailureClassifier` | Los errores de negocio, validación y seguridad son `rejected` con el código del catálogo como motivo; los errores técnicos y externos son `error` con su código. |
| `AxizMetricsAutoConfiguration` | Métricas genéricas de eventos: la API `EventMetrics`, `Metered`, el meter filter que aplica los tags y buckets declarados y los tags comunes `service`, `env`, `version` y `domain`. |
| `AxizMetricsProperties` | Métricas genéricas de eventos (`axiz.metrics.*`), normalmente declaradas en el ConfigMap del servicio. |
| `MeteredInterceptor` | Aplica `Metered`: cuenta la llamada, la mide y deriva el resultado y el motivo a partir del fallo. |

## Repositorios reactivos

Con Spring Data y WebFlux presentes, el framework resuelve el registro de métricas de repositorios al
arranque. Los callbacks de finalización de los repositorios reutilizan ese registro inicializado y
conservan los tags, el nombre del timer y la configuración de autotimer de Boot. Un listener definido por
la aplicación tiene precedencia.

## Pruebas

Las pruebas unitarias están en `src/test/java` y documentan el comportamiento esperado; se ejecutan con `./mvnw -pl spring/axiz-metrics-spring-boot-autoconfigure verify`.

## Exemplars OTLP durante el arranque

`AxizOtlpExemplarsAutoConfiguration` mantiene los callbacks de registro de métricas independientes de la
resolución de beans de Spring. Durante la creación de singletons no aporta exemplar de traza; los valores
de las métricas y las trazas siguen activos. Después de la inicialización de singletons usa el tracer en
caché. Esto evita que un callback de conexión de Redis espere el lock del BeanFactory que retiene el hilo
de arranque.

La configuración respeta `management.tracing.exemplars.include` (`none`, `all`, `sampled-traces`) y un
`ExemplarContextProvider` provisto por la aplicación. Solo se activa cuando están presentes las clases de
trazas y de exemplars OTLP. Se conservan las políticas por defecto y los IDs de traza y de span.
