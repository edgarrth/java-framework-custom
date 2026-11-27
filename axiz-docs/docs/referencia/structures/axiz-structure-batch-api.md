<!-- GENERADO por scripts/sync_reference.py desde axiz-framework: no editar -->
# axiz-structure-batch-api

Estilo batch sobre Spring Batch: jobs reiniciables, pasos por bloques, archivos en S3 y ejecución como CronJob o con ShedLock (contratos, Java simple).

## Dependencia

```xml
<dependency>
  <groupId>pe.axiz</groupId>
  <artifactId>axiz-structure-batch-api</artifactId>
</dependency>
```

La versión la gestiona `axiz-bom` (los servicios heredan `axiz-parent-service`).

## Tipos principales

| Tipo | Propósito |
|---|---|
| `CsvRecordReader` | Registros CSV mapeados a objetos; la cabecera puede omitirse y las líneas en blanco se ignoran. |
| `FixedWidthRecordReader` | Registros de ancho fijo (archivos de sistemas centrales) parseados con un layout; las líneas de cabecera y de trailer se reconocen con predicados y se omiten (el trailer se conserva para los totales de control). |
| `JobOutcome` | Resumen de un job terminado que se usa para métricas, auditoría y el código de salida del CronJob. |
| `RecordReader` | Lector secuencial de registros de archivo con seguimiento de líneas, para que los jobs puedan reiniciarse desde el último registro confirmado y los errores señalen la línea problemática. |

## Pruebas

Las pruebas unitarias están en `src/test/java` y documentan el comportamiento esperado; se ejecutan con `./mvnw -pl structures/batch/axiz-structure-batch-api verify`.
