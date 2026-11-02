# Batch

Procesos de archivos, conciliaciones y cargas sobre **Spring Batch 6**, ejecutados como
**CronJob** de Kubernetes (o con ShedLock dentro de un servicio).

**Reglas** (`BatchRules`): los `*Processor` viven en el paquete `job` y no hay controladores REST.

## Qué aporta el estilo

- **Sin servidor web** y salida del proceso con el estado del job: el CronJob ve un código distinto
  de cero si el job falla (el `main` generado termina con `SpringApplication.exit`).
- **Lectura reiniciable desde S3**: `StorageRecordItemReader` guarda el conteo de ítems en el
  contexto del step, así un job fallido retoma después del último chunk confirmado.
- **Formatos**: `CsvRecordReader`, `FixedWidthRecordReader` (cabecera y trailer reconocidos, el
  trailer queda para los totales de control) y `ControlTotals`, `Checksums`, EBCDIC/COMP-3.
- **Observación de cada job**: `ObservedJobListener` registra el evento `batch.run` con duración y
  registros, audita la ejecución y deja una línea de resumen.
- **Esquema por Flyway** fuera del perfil local (`spring.batch.jdbc.initialize-schema: never`).
- **Clase de configuración con otro nombre que el job** (`SettlementJobConfiguration` declara
  `settlementJob()`): dos beans con el mismo nombre detienen el arranque, y la regla
  `UNIQUE_BEAN_NAMES` del `ArchitectureTest` lo marca.

```java
@Bean
Job settlementJob(JobRepository jobs, Step validateStep, Step loadStep, Step summaryStep) {
  return new JobBuilder("settlement", jobs).start(validateStep).next(loadStep).next(summaryStep).build();
}

@Bean
@StepScope
StorageRecordItemReader<SettlementLine> settlementReader(
    ObjectStorage storage, @Value("#{jobParameters['input.key']}") String inputKey) {
  return StorageRecordItemReader.named("settlementReader", storage, inputKey, SettlementFile::reader);
}
```

Carga idempotente: el writer inserta con `ON CONFLICT DO NOTHING` sobre la llave de negocio, y el
parámetro identificador del job es el archivo, así relanzar el mismo archivo continúa la misma
ejecución en lugar de duplicarla.

## Despliegue

El chart genera un `CronJob` con `concurrencyPolicy: Forbid`, zona horaria, `startingDeadlineSeconds`,
`backoffLimit`, `activeDeadlineSeconds` e historial de ejecuciones exitosas y fallidas.

En el demo, `settlement-batch` concilia el archivo de liquidación (ancho fijo) que deja la red de
pagos en S3: valida totales de control, carga en Aurora y publica un resumen en S3.
