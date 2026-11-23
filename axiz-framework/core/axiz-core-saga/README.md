<!-- generated -->
# axiz-core-saga

Sagas orquestadas con reintentos, compensación y estado persistente y reanudable.

## Dependencia

```xml
<dependency>
  <groupId>pe.axiz</groupId>
  <artifactId>axiz-core-saga</artifactId>
</dependency>
```

La versión la gestiona `axiz-bom` (los servicios heredan `axiz-parent-service`).

## Tipos principales

| Tipo | Propósito |
|---|---|
| `ContextCodec` | Serializa los contextos de saga (JSON con el mapper del servicio en el starter). |
| `InMemorySagaStateStore` | Almacén de sagas en memoria para pruebas y ejecuciones locales. |
| `SagaConcurrencyException` | Otra instancia actualizó la saga de forma concurrente. |
| `SagaDefinition` | Pasos ordenados de una saga. |
| `SagaListener` | Observa el avance de la saga (métricas, trazas y alertas). |
| `SagaOrchestrator` | Ejecuta sagas: corre los pasos en orden (con reintentos ante fallos transitorios), persiste el estado después de cada paso y, cuando un paso falla, compensa los pasos completados en orden inverso. |
| `SagaRecovery` | Reanuda las sagas que quedaron sin terminar por una caída o un redespliegue: las sagas no terminales detenidas se adquieren de forma exclusiva y los efectos inciertos se verifican antes de continuar. |
| `SagaResult` | Resultado de ejecutar una saga. |
| `SagaState` | Estado persistido de una instancia de saga. |
| `SagaStateStore` | Persistencia del estado de saga con leases de propiedad y actualizaciones versionadas. |
| `SagaStatus` | Ciclo de vida de la saga. |
| `SagaStep` | Un paso de una saga: una acción, su compensación y, para efectos remotos, cómo verificar un resultado incierto. |
| `SagaStepRejectedException` | Respuesta definitiva de un participante: el paso fue rechazado y su efecto no existe (por ejemplo, fondos insuficientes). |
| `StepCheck` | Lo que dice un participante sobre un paso de resultado incierto (timeout, respuesta perdida, error del servidor): el efecto se aplicó, no se aplicó o todavía no se sabe. |
| `StepFailures` | Clasifica los fallos de un paso: rechazos definitivos (el efecto no existe) frente a resultados inciertos (el efecto puede existir). |

## Pruebas

Las pruebas unitarias están en `src/test/java` y documentan el comportamiento esperado; se ejecutan con `./mvnw -pl core/axiz-core-saga verify`.

## JDBC

La implementación JDBC y los recursos SQL están en `axiz-adapter-saga-jdbc`; este módulo contiene contratos y
orquestación neutrales respecto del framework, y `TransactionRunner` está en `pe.axiz.core.commons.tx`. Un servicio agrega el
adaptador JDBC de una capacidad con `axiz add` (o con el Initializr); ver
[límites JDBC](../../docs/architecture/JDBC.md).

## Propiedad de la ejecución y efectos inciertos

Cada ejecución tiene un lease renovable en el mismo almacén que el estado de la saga. `start`,
`resume`, la verificación y la compensación usan el mismo límite de propiedad; un lease activo
excluye a cualquier otro ejecutor. Cada escritura comprueba de forma atómica el propietario, el
plazo del lease y la versión del estado. Un lease rechazado no puede renovarse ni usarse para
escribir. JDBC usa la hora de la base y conexiones independientes confirmadas; la orquestación no
se envuelve en una transacción del llamador.

Antes de llamar a un participante, el orquestador persiste la intención como `IN_DOUBT`.
`SagaStepExecution` entrega el identificador de la saga, el nombre del paso y la versión creciente
del estado como valor de exclusión. Los participantes aplican la exclusión y la idempotencia en su
propio límite de escritura durable; enviar un número por sí solo no detiene un efecto externo
tardío. La idempotencia sigue siendo responsabilidad de los pasos.

`StepCheck.notApplied()` solo significa que el efecto no es visible todavía: la saga sigue en
`IN_DOUBT`. `StepCheck.rejected()` se devuelve únicamente cuando el participante garantiza de forma
durable que el efecto nunca llegará (incluidos los intentos encolados), por ejemplo con una marca de
cancelación. El trabajo de resultado desconocido nunca se compensa de forma automática.

`axiz.saga.execution-lease` vale 30 segundos por defecto (de 100 ms a 10 minutos). La renovación y el
vencimiento del lease permiten la recuperación, pero no prueban que el trabajo remoto terminó. Los
estados `IN_DOUBT` antiguos se monitorean.
