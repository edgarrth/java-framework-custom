# Hexagonal

Para dominios con reglas: el dominio al centro en Java puro, los casos de uso alrededor y los
adaptadores afuera. Las dependencias solo apuntan hacia adentro.

```text
<base>/
├── domain/                 agregados, value objects, eventos de dominio, errores (Java puro)
├── application/
│   ├── usecase/            un caso de uso por operación
│   └── port/out/           puertos de salida (repositorios, clientes, publicadores)
├── adapter/
│   ├── in/rest/            controladores generados desde el contrato
│   ├── in/messaging/       handlers de eventos
│   └── out/                persistencia, clientes HTTP, mensajería
└── config/                 cableado de casos de uso, puertos y adaptadores
```

**Reglas** (`HexagonalRules`): los adaptadores solo son accedidos por `config`; la aplicación por
adaptadores y `config`; el dominio no depende de Spring, JPA, el AWS SDK ni Jackson; la aplicación
no depende de Spring Web, JPA ni el AWS SDK; los `*UseCase` viven en `application`, los
`*Controller` en `adapter.in` y los `*Adapter` en `adapter`.

## Casos de uso

```java
/** Holds and debits funds of an account in the core, one movement per account at a time. */
@UseCase
public class DebitAccountUseCase
    implements pe.axiz.structure.hexagonal.UseCase<DebitAccountUseCase.Input, Movement> {

  public record Input(MovementOrder order, String idempotencyKey) {}

  private final CoreAccounts core;          // puerto de salida
  private final LockTemplate locks;
  private final EventMetrics metrics;

  public DebitAccountUseCase(CoreAccounts core, LockTemplate locks, EventMetrics metrics) {
    this.core = core;
    this.locks = locks;
    this.metrics = metrics;
  }

  @Override
  @Auditable(action = "account.debit", resource = "account", captureArguments = false)
  public Movement execute(Input input) {
    Movement movement =
        AccountLocks.with(locks, input.order().lockKey(),
            () -> core.debit(input.order(), input.idempotencyKey()));
    metrics.record(MetricEvent.of("core.movement").category("debit")
        .amount(input.order().amount()).outcome(Outcome.SUCCESS));
    return movement;
  }
}
```

(De la demo, `banking-core`.) La anotación `@UseCase` y la interfaz `UseCase` comparten nombre:
se importa la anotación y la interfaz se escribe calificada, como en el código generado.

Cada clase `@UseCase` corre con:

- una **observación** `axiz.usecase` (span y métricas con la operación y el resultado);
- una **transacción** (se une a una existente);
- el ciclo de **eventos de dominio**: lo que el agregado registra (`AggregateRoot`) se entrega al
  confirmar. Con la capacidad `outbox`, `OutboxDomainEventSink` los escribe en el outbox dentro de
  la misma transacción (se publican si y solo si el cambio se confirma); sin outbox se publican en
  proceso después del commit.

Si la publicación en proceso falla **después de confirmar**, `UseCaseRunner` lanza
`PostCommitPublicationException` con el código `AXZ-CORE-0410`, HTTP 409 y semántica no
reintentable. Implementa `CommittedTransactionOutcome`, y la política de reintentos reconoce
ese marcador incluso si otra excepción lo envuelve. El cambio de negocio ya existe: el
consumidor debe consultar su resultado o reconciliar la publicación pendiente. Volver a
ejecutar el comando como si hubiera hecho rollback puede duplicar efectos. Para entrega durable,
seleccionar outbox mantiene la escritura del evento dentro de la transacción.

La composición reactiva también funciona en procesos sin servidor web. Una aplicación worker
puede seleccionar el stack reactivo y suministrar su `ReactiveTransactionManager`; la
autoconfiguración no depende de que exista un contexto web de tipo REACTIVE. Debe ejecutar el
flujo mediante la composición de Axiz, preservando el contexto de Reactor, y no llamar a
`block()` dentro del hilo de eventos.

## Puertos y adaptadores

Los puertos son interfaces del paquete `application/port/out` (anotadas `@OutboundPort` si se
quiere documentarlas); los adaptadores las implementan en `adapter/out` y traducen modelos y
errores del exterior (por ejemplo `CoreBankingAdapter` en el demo convierte `CB-51` en
"fondos insuficientes" del dominio). Los clientes generados con `axiz generate http-client` caen en
`adapter/out/<cliente>`.
