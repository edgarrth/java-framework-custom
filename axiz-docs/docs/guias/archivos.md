# Archivos y batch

## Almacenamiento (S3)

`ObjectStorage` es el puerto; `S3ObjectStorage` lo implementa con subida y descarga en streaming,
SSE-KMS con la llave configurada y URLs prefirmadas con TTL máximo.

```java
storage.put("out/settlement/" + name, content, ObjectMetadata.of("application/json", length));
Optional<StoredObject> file = storage.get(key);   // StoredObject se cierra para liberar la conexión
```

`ObjectKey` valida las llaves (relativas, sin `..`, sin caracteres de control): las rutas nunca se
arman con datos del usuario sin validar.

## Formatos

| Tipo | Para qué |
|---|---|
| `CsvReader` / `CsvWriter` | RFC 4180 en streaming: comillas, comillas escapadas, saltos de línea dentro de campos |
| `FixedWidthLayout`, `FixedField`, `FieldType` | registros de ancho fijo (legacy): parseo tipado y formateo |
| `ControlTotals` | conteo y suma acumulados contra el trailer del archivo |
| `Checksums` | integridad con sistemas legacy |
| `Ebcdic` | texto EBCDIC y decimal empaquetado (COMP-3) de mainframes |
| `FileFormatException` | registro malformado con su número de línea |

```java
// posición inicial (1-based) y largo de cada campo; decimal con escala implícita
static final FixedWidthLayout DETAIL = new FixedWidthLayout("settlement-detail", List.of(
    FixedField.text("type", 1, 1),
    FixedField.text("endToEndId", 2, 20),
    FixedField.text("cci", 34, 20),
    FixedField.decimal("amount", 54, 15, 2),
    FixedField.text("currency", 69, 3)));

Map<String, Object> record = DETAIL.parse(line, lineNumber);   // FileFormatException con la línea
```

## Jobs

Ver el estilo [Batch](../estructuras/batch.md): lectores reiniciables sobre S3
(`StorageRecordItemReader` con `CsvRecordReader` o `FixedWidthRecordReader`), totales de control
en un step de validación, escritura idempotente, observación de cada ejecución y código de salida
del proceso para el CronJob.

En el demo, `settlement-batch` lee `in/settlement/NET-SETTLE-<fecha>.txt` (detalle y trailer de
ancho fijo), valida totales, carga en Aurora y escribe `out/settlement/<archivo>.summary.json`.
