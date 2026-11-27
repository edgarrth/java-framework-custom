# axiz-adapter-observability-aws

Instrumentación con Micrometer sobre `axiz-adapter-aws`; aquí no se duplica ninguna implementación de credenciales ni de transporte.

| API | Responsabilidad |
|---|---|
| `AwsObservability` | Compone clientes síncronos y nativos con un plugin de observación del SDK; conserva la configuración del builder y evita interceptores duplicados. |
| `ObservationExecutionInterceptor` | Registra observaciones por servicio y operación y propaga las cabeceras de trazas antes de firmar. |
| `AwsCompletionMetrics` | Vincula las estadísticas de admisión de finalizaciones a un `MeterRegistry` de aplicación entregado de forma explícita. |

La base AWS no depende de este módulo ni de Micrometer. La composición de Spring entrega el registro
del contexto y es dueña de la vida de la vinculación; sin registro crea el transporte sin métricas.
No se usa un registro global de respaldo ni se descubre instrumentación por el classpath. Cerrar o
volver a vincular recursos elimina sus meters, y los nombres iguales quedan aislados entre registros.

Quien usa el framework compone con los starters de cada capacidad. Fuera del framework se puede usar
`AwsObservability` con un registro propio, o las fábricas base directamente sin instrumentación. Los
recursos y la política de credenciales están en `pe.axiz.adapter.aws.identity`; la propiedad de
clientes y workers está en `pe.axiz.adapter.aws.transport`. Los SDK reciben vistas de credenciales
que no cierran.

Se ejecuta `./mvnw -pl adapters/axiz-adapter-observability-aws -am verify`.
