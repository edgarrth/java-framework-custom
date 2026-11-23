<!-- GENERADO por scripts/sync_reference.py desde axiz-framework: no editar -->
# axiz-structure-batch-spring-boot-starter

Estilo batch sobre Spring Batch: jobs reiniciables, pasos por bloques, archivos en S3 y ejecución como CronJob o con ShedLock.

## Dependencia

```xml
<dependency>
  <groupId>pe.axiz</groupId>
  <artifactId>axiz-structure-batch-spring-boot-starter</artifactId>
</dependency>
```

La versión la gestiona `axiz-bom` (los servicios heredan `axiz-parent-service`).

## Tipos principales

| Tipo | Propósito |
|---|---|
| `AxizBatchAutoConfiguration` | Estilo batch sobre Spring Batch: cada job informa métricas, auditoría y una línea de resumen; los lectores de archivos en S3 se reinician desde el último bloque confirmado. |
| `AxizBatchEnvironmentPostProcessor` | Valores por defecto de batch: sin servidor web (CronJob), el job se ejecuta al arrancar y el proceso termina con su estado; fuera de las ejecuciones locales, Flyway gestiona el esquema de Spring Batch. |
| `ObservedJobListener` | Métricas del job (evento `batch.run` con duración y registros), auditoría de cada ejecución y una línea de log de resumen. |
| `ObservedJobListenerPostProcessor` | Registra `ObservedJobListener` en cada bean de job. |
| `StorageRecordItemReader` | Lector reiniciable de Spring Batch sobre un archivo en almacenamiento de objetos (S3): la cantidad de ítems se guarda en el contexto de ejecución del paso, de modo que un job fallido se reanuda después del último bloque confirmado. |

## Pruebas

Las pruebas unitarias están en `src/test/java` y documentan el comportamiento esperado; se ejecutan con `./mvnw -pl structures/batch/axiz-structure-batch-spring-boot-starter verify`.
