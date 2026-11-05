<!-- generated -->
# axiz-core-saga

Orchestrated sagas with retries, compensation and persistent, resumable state.

## Dependency

```xml
<dependency>
  <groupId>pe.axiz</groupId>
  <artifactId>axiz-core-saga</artifactId>
</dependency>
```

The version is managed by `axiz-bom` (services inherit `axiz-parent-service`).

## Main types

| Type | Purpose |
|---|---|
| `ContextCodec` | Serializes saga contexts (JSON with the service mapper in the starter). |
| `InMemorySagaStateStore` | In-memory saga store for tests and local runs. |
| `SagaConcurrencyException` | Another instance updated the saga concurrently. |
| `SagaDefinition` | Ordered steps of a saga. |
| `SagaListener` | Observes saga progress (metrics, traces, alerts). |
| `SagaOrchestrator` | Runs sagas: executes steps in order (with retries for transient failures), persists state after every step and, when a step fails, compensates the completed steps in reverse order. |
| `SagaRecovery` | Resumes sagas left unfinished by a crash or a redeploy: non-terminal sagas not updated for a while are resumed from their persisted state (steps are idempotent, so repeating the last one is safe). |
| `SagaResult` | Outcome of running a saga. |
| `SagaState` | Persisted state of a saga instance. |
| `SagaStateStore` | Persistence of saga state with optimistic locking. |
| `SagaStatus` | Saga lifecycle. |
| `SagaStep` | One step of a saga: an action, its compensation and, for remote effects, how to verify an uncertain outcome. |
| `SagaStepRejectedException` | Definite answer of a participant: the step was rejected and its effect does not exist (for example insufficient funds). |
| `StepCheck` | What a participant says about a step whose outcome was uncertain (timeout, lost response, server error): the effect was applied, was not applied, or nobody knows yet. |
| `StepFailures` | Classifies step failures: definite rejections (the effect does not exist) against uncertain outcomes (the effect may exist). |

## Tests

Unit tests live in `src/test/java` and document the expected behavior; run them with `./mvnw -pl core/axiz-core-saga verify`.

## JDBC extraction in 2.0

The JDBC implementation and SQL resources now live in `axiz-adapter-saga-jdbc`; this module contains
framework-neutral contracts and orchestration. `TransactionRunner` remains in
`pe.axiz.core.commons.tx`. Use the capability catalog/`axiz upgrade` to add JDBC adapters with
`runtime` scope in a service. See [JDBC migration](../../docs/migration/2.0/JDBC.md).
