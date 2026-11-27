<!-- GENERADO por scripts/sync_reference.py desde axiz-framework: no editar -->
# axiz-core-logging

Campos de log estructurados, MDC por ámbito, registro de operaciones y lista de cabeceras permitidas.

## Dependencia

```xml
<dependency>
  <groupId>pe.axiz</groupId>
  <artifactId>axiz-core-logging</artifactId>
</dependency>
```

La versión la gestiona `axiz-bom` (los servicios heredan `axiz-parent-service`).

## Tipos principales

| Tipo | Propósito |
|---|---|
| `HttpLogFormatter` | Formatea los logs de petición y respuesta HTTP: solo se escriben las cabeceras permitidas (nunca Authorization ni Cookie) y los cuerpos JSON se enmascaran por clave y se truncan. |
| `LogFields` | Nombres estándar de los campos de log estructurado (claves MDC). |
| `LogOperation` | Registra la entrada y la salida de un método (o de todos los métodos públicos de una clase) con duración, resultado y argumentos y retorno enmascarados. |
| `NoLog` | Excluye un parámetro, el resultado de un método o un método completo de los logs de operación y HTTP. |
| `OperationLogger` | Escribe las líneas de entrada y salida de una operación. |
| `ScopedMdc` | Coloca valores en el MDC durante un ámbito y restaura los valores previos al cerrarse. |

## Pruebas

Las pruebas unitarias están en `src/test/java` y documentan el comportamiento esperado; se ejecutan con `./mvnw -pl core/axiz-core-logging verify`.
