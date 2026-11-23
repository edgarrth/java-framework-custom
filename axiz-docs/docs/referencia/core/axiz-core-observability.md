<!-- GENERADO por scripts/sync_reference.py desde axiz-framework: no editar -->
# axiz-core-observability

Convenciones de observación, atributos estándar y utilidades para los spans del framework y los eventos estructurados.

## Dependencia

```xml
<dependency>
  <groupId>pe.axiz</groupId>
  <artifactId>axiz-core-observability</artifactId>
</dependency>
```

La versión la gestiona `axiz-bom` (los servicios heredan `axiz-parent-service`).

## Tipos principales

| Tipo | Propósito |
|---|---|
| `ArchitectureObservation` | Crea los spans del framework en los puntos de arquitectura (caso de uso, comando, consulta, handler de eventos, outbox, paso de saga, etc.). |
| `AttributeSanitizer` | Protege los atributos de spans y los eventos estructurados de datos personales: descarta las claves prohibidas y oculta los valores que parecen números de tarjeta o correos. |
| `AxizAttributes` | Claves estándar de atributos. |
| `AxizObservations` | Nombres de las observaciones (spans y timers) que crea el framework en los puntos de arquitectura. |
| `LoggingStructuredEventEmitter` | Emite eventos estructurados al logger `axiz.event` y a la observación actual. |
| `Outcomes` | Clasifica el resultado de una operación en los valores de resultado estándar. |
| `StructuredEvent` | Evento estructurado que se escribe como línea de log JSON y como evento del span actual. |
| `StructuredEventEmitter` | Puerto para emitir eventos estructurados; el starter de observabilidad los registra y agrega eventos al span. |

## Pruebas

Las pruebas unitarias están en `src/test/java` y documentan el comportamiento esperado; se ejecutan con `./mvnw -pl core/axiz-core-observability verify`.
