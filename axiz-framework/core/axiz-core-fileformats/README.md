<!-- generated -->
# axiz-core-fileformats

Registros CSV y de ancho fijo, conversión EBCDIC, checksums y trailers de control.

## Dependencia

```xml
<dependency>
  <groupId>pe.axiz</groupId>
  <artifactId>axiz-core-fileformats</artifactId>
</dependency>
```

La versión la gestiona `axiz-bom` (los servicios heredan `axiz-parent-service`).

## Tipos principales

| Tipo | Propósito |
|---|---|
| `Checksums` | Checksums de archivos para controles de integridad con sistemas centrales. |
| `ControlTotals` | Totales acumulados que se contrastan con el trailer del archivo (cantidad de registros y suma de importes). |
| `CsvReader` | Lector CSV RFC 4180 en streaming: campos entre comillas, comillas escapadas (`""`), delimitadores y saltos de línea dentro de comillas, CRLF o LF. |
| `CsvWriter` | Escritor de CSV según RFC 4180. |
| `Ebcdic` | Conversión de texto EBCDIC y decimal empaquetado (COMP-3) para archivos intercambiados con mainframes. |
| `FieldType` | Tipos de campos de ancho fijo. |
| `FileFormatException` | Registro mal formado en un archivo; lleva el número de línea. |
| `FixedField` | Un campo de un registro de ancho fijo. |
| `FixedWidthLayout` | Diseño de un registro de ancho fijo: convierte líneas en valores tipados y formatea los valores de vuelta. |

## Pruebas

Las pruebas unitarias están en `src/test/java` y documentan el comportamiento esperado; se ejecutan con `./mvnw -pl core/axiz-core-fileformats verify`.
