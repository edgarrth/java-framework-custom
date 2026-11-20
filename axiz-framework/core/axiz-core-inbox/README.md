<!-- generated -->
# axiz-core-inbox

Idempotent consumer contracts and orchestration; storage belongs to the provider adapters.

## Dependency

```xml
<dependency>
  <groupId>pe.axiz</groupId>
  <artifactId>axiz-core-inbox</artifactId>
</dependency>
```

The version is managed by `axiz-bom` (services inherit `axiz-parent-service`).

## Main types

| Type | Purpose |
|---|---|
| `InboxDeduplicator` | Connects the inbox to the event dispatcher: a message is processed once per consumer within the retention period. |
| `InboxException` | Inbox persistence failure. |
| `InboxStore` | Registry of consumed messages per consumer (idempotent consumer pattern). |
| `AsyncInboxStore` | CompletionStage port with the same owner fencing, leases and retention semantics. |

## Tests

Unit tests live in `src/test/java` and document the expected behavior; run them with `./mvnw -pl core/axiz-core-inbox verify`.

## JDBC extraction in 2.0

The JDBC implementation and SQL resources now live in `axiz-adapter-inbox-jdbc`; this module contains
framework-neutral contracts and orchestration. `TransactionRunner` remains in
`pe.axiz.core.commons.tx`. Use the capability catalog/`axiz upgrade` to add JDBC adapters with
`runtime` scope in a service. See [JDBC migration](../../docs/architecture/JDBC.md).

## Retention and recovery

Require `lease > 0` and `retention > lease`. The lease deadline must be after acquisition and the
retention deadline strictly after the lease. A takeover renews both deadlines and fences the old
owner. Purging removes completed records only; an in-progress record is recovered through its
lease, never deleted by retention. A handler must finish within its lease or enforce participant
fencing/idempotency itself; the inbox does not cancel a late external effect.

DynamoDB keeps `expiresAt` for in-progress records and adds `ttl` only when processing completes.
When upgrading a table with legacy in-progress `ttl` attributes, stop consumers, disable TTL and
wait for DynamoDB to finish disabling it. Copy each legacy retention deadline to `expiresAt`
and remove `ttl` in the same conditional update, checking the status and original values.
Reconcile records with missing deadlines or retention that does not outlive the lease;
do not invent a new retention horizon. Then re-enable TTL on `ttl` and resume.
Completion requires `expiresAt`; partially migrated records fail closed.
TTL deletions already in progress cannot be undone; reconcile
those messages before reopening. This migration is operated by the table owner, never silently
executed by the framework. Monitor and recover abandoned in-progress records explicitly.

The retention horizon is measured from successful acquisition/takeover, not from completion.
The supported processing window ends before the lease deadline, which is strictly earlier than
retention. A handler exceeding that window needs participant idempotency/fencing and reconciliation;
it must not assume that the inbox provides exactly-once external effects. Inbox time arguments are
trusted server times: synchronize nodes with monitored NTP and budget the maximum clock skew when
choosing leases. A lease alone cannot prove termination of a suspended or partitioned handler.

### Inventario de registros abandonados en DynamoDB

Un `IN_PROGRESS` con lease vencido puede corresponder a un mensaje en DLQ. El operador debe
inventariarlos por páginas y correlacionarlos con el consumidor, la DLQ y el estado del efecto
externo; el vencimiento no demuestra que ese efecto haya terminado. Para una primera página
local (sustituya `CUTOFF_EPOCH_MILLIS` por el instante de corte de su monitor):

```bash
aws --endpoint-url http://localhost:4566 dynamodb scan \
  --table-name axiz-inbox --consistent-read --limit 100 --no-paginate \
  --filter-expression '#state = :progress AND leaseUntil < :cutoff' \
  --projection-expression 'pk, #state, #owner, leaseUntil, expiresAt' \
  --expression-attribute-names '{"#state":"status","#owner":"owner"}' \
  --expression-attribute-values '{":progress":{"S":"IN_PROGRESS"},":cutoff":{"N":"CUTOFF_EPOCH_MILLIS"}}'
```

Continúe con `--exclusive-start-key` usando `LastEvaluatedKey`, incluso si una página filtrada
está vacía; limite la capacidad de lectura y la duración del sondeo. El monitor puede emitir un
contador agregado y edad máxima, sin usar `pk`, owner o valores de negocio como etiquetas.
Una alerta debe abrir conciliación operativa; nunca añada TTL ni borre registros automáticamente
sólo porque el lease expiró. El relevo normal conserva la condición atómica de propietario/lease.
