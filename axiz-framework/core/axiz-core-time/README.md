<!-- generated -->
# axiz-core-time

Reloj inyectable, calendario de negocio, feriados, horas de corte y fechas de vencimiento.

## Dependencia

```xml
<dependency>
  <groupId>pe.axiz</groupId>
  <artifactId>axiz-core-time</artifactId>
</dependency>
```

La versión la gestiona `axiz-bom` (los servicios heredan `axiz-parent-service`).

## Tipos principales

| Tipo | Propósito |
|---|---|
| `BusinessCalendar` | Calendario de días hábiles: fines de semana, feriados recurrentes (mismo día cada año) y feriados puntuales. |
| `CutoffTime` | Corte diario: las operaciones recibidas a la hora de corte o después, o en un día no hábil, se procesan el siguiente día hábil. |
| `DueDates` | Cálculo de vencimientos con convenciones de ajuste a día hábil. |
| `TimeSettings` | Configuración de tiempo de un servicio. |

## Pruebas

Las pruebas unitarias están en `src/test/java` y documentan el comportamiento esperado; se ejecutan con `./mvnw -pl core/axiz-core-time verify`.
