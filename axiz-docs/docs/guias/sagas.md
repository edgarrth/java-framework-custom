# Sagas

Un proceso que abarca varios servicios no puede usar una transacción distribuida: se modela como
una **saga orquestada**, una secuencia de pasos con su compensación.

```java
new SagaDefinition<>("payment", List.of(
    step("processing", c -> orders.markProcessing(...), c -> orders.reject(...)),
    step("debit",      c -> c.debited(accounts.debit(...)), c -> accounts.reverse(...)),
    step("transfer",   c -> c.sent(transfers.send(...)),    c -> {}),     // pivote
    step("complete",   c -> completeOrMarkPending(c),       c -> {})));
```

`SagaOrchestrator`:

- adquiere una reserva exclusiva por saga, persiste la intención antes del efecto y el resultado
  después; las escrituras verifican propietario, vigencia y versión;
- reintenta fallos transitorios de un paso (`axiz.saga.step-attempts`, backoff);
- si un paso falla, **compensa los completados en orden inverso**
  (`axiz.saga.compensation-attempts`);
- notifica el progreso a `SagaListener`s (métricas, trazas, alertas).

```java
SagaResult<PaymentContext> result =
    orchestrator.start(PaymentSaga.definition(orders, accounts, transfers), codec, sagaId, initialContext);
```

El contexto se serializa con un `ContextCodec` (JSON con el mapper del servicio:
`JacksonContextCodec`).

## Diseño de pasos

- **Idempotencia por paso**: cada acción y compensación lleva una llave derivada del id de la saga y
  del paso (ver [Idempotencia](idempotencia.md)).
- **Paso pivote**: después de él no se compensa (en el demo, la transferencia enviada a la red).
  Los pasos posteriores no deben fallar la saga: si no pueden completarse, dejan una marca para la
  conciliación (`completionPending`).
- **Compensaciones semánticas**: revertir un débito es un nuevo movimiento de reversa, no borrar.

## Resultado incierto

Un paso que falla no siempre significa "no pasó nada": si el débito se aplicó y se perdió la
respuesta, compensar desde el paso anterior dejaría el débito aplicado bajo una orden rechazada. El
orquestador distingue:

| Falla del paso | Qué hace |
|---|---|
| rechazo definitivo: `SagaStepRejectedException` o error 4xx de axiz (400, 403, 404, 422...) | el efecto no existe: compensa los pasos anteriores |
| incierta: timeout, 5xx, error de transporte, 409, 429, cualquier otra | pregunta al participante con `verify(context)` |
| `verify` responde `applied(ctx)` | el paso se aplicó: la saga sigue con ese contexto |
| `verify` responde `notApplied()` | ausencia observada: permanece `IN_DOUBT`, puede llegar un efecto tardío |
| `verify` responde `rejected()` | rechazo terminal durable que impide efectos futuros: compensa los anteriores |
| `verify` responde `unknown()` (por defecto) | la saga queda `IN_DOUBT`: no compensa ni declara resultado; la recuperación vuelve a preguntar |

```java
@Override
public StepCheck<PaymentContext> verify(PaymentContext c) {
  // consulta por la llave del paso, o la misma llamada idempotente con la misma llave
  return accounts.findDebit(c.instruction().keyFor("debit"))
      .map(movement -> StepCheck.applied(c.debited(movement)))
      .orElseGet(StepCheck::notApplied);
}
```

En el demo, débito y transferencia se verifican repitiendo la llamada con la misma llave
(los participantes devuelven el primer resultado para una llave conocida): la transferencia cuya
respuesta se perdió nunca provoca la reversa del débito. Alerta sobre `axiz.saga.in_doubt` y sobre
sagas `IN_DOUBT` antiguas: requieren conciliación o decisión de operaciones.

## Garantías del orquestador

- Un paso que se ejecutó se compensa siempre si la saga aborta, **también** cuando falló la
  escritura de su resultado en el store.
- Los listeners (`SagaListener`: métricas, trazas, alertas) solo observan: si fallan se registra un
  `WARN` y la saga sigue igual; nunca disparan ni alteran una compensación.
- La intención de compensar (`COMPENSATING`) se persiste **antes** de deshacer nada. Si ni eso se
  puede escribir, la excepción se propaga, no se deshace ningún paso y la saga queda `IN_DOUBT` para
  que la recuperación verifique el efecto pendiente.
- Una falla al registrar el avance de la compensación se propaga y la recuperación vuelve a
  compensar ese paso; nunca se reporta como compensación fallida.

Por eso **acciones y compensaciones deben ser idempotentes**: una caída entre ejecutarlas y
registrarlas las repite al reanudar.

## Recuperación

Si el pod muere a mitad de una saga, su estado queda persistido. `SagaRecovery` reanuda las sagas
inactivas por más de un umbral y sin una reserva vigente. Cada ejecución toma su reserva en el
store; varias réplicas pueden buscar trabajo sin ejecutar simultáneamente la misma saga. Un
ShedLock adicional puede limitar el escaneo, pero no sustituye esa propiedad por saga:

```java
@Scheduled(fixedDelayString = "${payments.process.recovery-interval:60s}")
@SchedulerLock(name = "payment-saga-recovery", lockAtMostFor = "5m")
public void resumeStale() {
  int resumed = recovery.resumeStale(Duration.ofMinutes(2), 50);
}
```

Las definiciones se registran una vez (`recovery.register(definition, codec)`).

En el demo, `payment-process` arranca la saga al consumir `payment-order.created.v1` y coordina
`payments-business` y `core-integration`.

## Migración y fencing de participantes

`SagaStepExecution` entrega id de saga, nombre de paso y versión creciente. El participante debe
validar esa versión en la misma transacción que aplica su efecto y mantener idempotencia durable.
Una reserva vencida impide nuevos avances del orquestador anterior, pero no cancela I/O ya enviado.
Una consulta vacía nunca autoriza compensar una petición todavía en vuelo.

La reserva se renueva y su duración se configura con `axiz.saga.execution-lease` (30 s por defecto).
JDBC usa las columnas `execution_owner` y `execution_until` de la tabla `axiz_saga`. El store
confirma el estado y la reserva en conexiones propias en auto-commit (`AutoCommitConnectionProvider`),
independientes de la transacción del llamante: el orquestador funciona igual dentro de un `@UseCase`
o de un handler de eventos transaccional, y un rollback del llamante no borra el estado de la saga.
