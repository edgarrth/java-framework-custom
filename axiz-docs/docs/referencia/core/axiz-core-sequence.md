<!-- GENERADO por scripts/sync_reference.py desde axiz-framework: no editar -->
# axiz-core-sequence

Secuencias atómicas con formato, con puertos síncrono y asíncrono nativo. PostgreSQL usa una tabla
de contadores; DynamoDB usa contadores atómicos a través del adaptador tecnológico.

## Dependencia

```xml
<dependency>
  <groupId>pe.axiz</groupId>
  <artifactId>axiz-core-sequence</artifactId>
</dependency>
```

La versión la gestiona `axiz-bom` (los servicios heredan `axiz-parent-service`).

## Tipos principales

| Tipo | Propósito |
|---|---|
| `AsyncSequenceGenerator` | Asignación asíncrona de bloques solo con el JDK, con cancelación y reservas compartidas. |
| `AsyncSequenceStore` | Puerto nativo asíncrono de contador durable. |
| `InMemorySequenceStore` | Contadores en memoria para pruebas y ejecuciones locales. |
| `ResetPolicy` | Cuándo una secuencia con formato vuelve a empezar desde 1. |
| `SequenceExhaustedException` | La secuencia superó los dígitos de su formato dentro del periodo. |
| `SequenceFormat` | Cómo se representa un valor de la secuencia: `prefix + [period + separator] + valor con ceros a la izquierda`. |
| `SequenceGenerator` | Secuencia formateada que tolera huecos. |
| `SequenceStore` | Contadores atómicos por nombre y periodo. |

## Garantías de reserva

Una reserva de bloque exitosa ya debe estar confirmada con independencia de la transacción de
negocio. Un rollback no debe devolver un bloque que otra petición o réplica aún tiene en caché. Se
esperan huecos tras un reinicio, una cancelación o una transacción de negocio fallida.

El generador asíncrono comparte las reservas en curso. Cancelar a uno de los que esperan conserva a
los demás; cancelar al último cancela el futuro nativo. Ningún generador da la vuelta a un contador
negativo después de `Long.MAX_VALUE`. El formato y la reserva usan el mismo periodo capturado.

## Pruebas

Las pruebas unitarias están en `src/test/java` y documentan el comportamiento esperado; se ejecutan con `./mvnw -pl core/axiz-core-sequence verify`.

## JDBC

La implementación JDBC y los recursos SQL están en `axiz-adapter-sequence-jdbc`; este módulo contiene contratos y
orquestación neutrales respecto del framework, y `TransactionRunner` está en `pe.axiz.core.commons.tx`. Un servicio agrega el
adaptador JDBC de una capacidad con `axiz add` (o con el Initializr); ver
[límites JDBC](../../arquitectura/jdbc.md).
