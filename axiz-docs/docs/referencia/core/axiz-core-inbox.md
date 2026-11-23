<!-- GENERADO por scripts/sync_reference.py desde axiz-framework: no editar -->
# axiz-core-inbox

Contratos y orquestación del consumidor idempotente; el almacenamiento pertenece a los adaptadores de proveedor.

## Dependencia

```xml
<dependency>
  <groupId>pe.axiz</groupId>
  <artifactId>axiz-core-inbox</artifactId>
</dependency>
```

La versión la gestiona `axiz-bom` (los servicios heredan `axiz-parent-service`).

## Tipos principales

| Tipo | Propósito |
|---|---|
| `InboxDeduplicator` | Conecta el inbox con el despachador de eventos: un mensaje se procesa una vez por consumidor dentro del periodo de retención. |
| `InboxException` | Fallo de persistencia del inbox. |
| `InboxStore` | Registro de los mensajes consumidos por consumidor (patrón de consumidor idempotente). |
| `AsyncInboxStore` | Puerto CompletionStage con la misma semántica de exclusión del propietario, leases y retención. |

## Pruebas

Las pruebas unitarias están en `src/test/java` y documentan el comportamiento esperado; se ejecutan con `./mvnw -pl core/axiz-core-inbox verify`.

## JDBC

La implementación JDBC y los recursos SQL están en `axiz-adapter-inbox-jdbc`; este módulo contiene contratos y
orquestación neutrales respecto del framework, y `TransactionRunner` está en `pe.axiz.core.commons.tx`. Un servicio agrega el
adaptador JDBC de una capacidad con `axiz add` (o con el Initializr); ver
[límites JDBC](../../arquitectura/jdbc.md).

## Retención y recuperación

Se exige `lease > 0` y `retention > lease`. El plazo del lease debe ser posterior a la adquisición y
el de retención estrictamente posterior al del lease. Una toma de control renueva ambos plazos y
excluye al propietario anterior. La purga elimina solo registros completados; un registro en curso
se recupera mediante su lease y nunca lo borra la retención. Un handler termina dentro de su lease
o aplica por sí mismo la exclusión o idempotencia del participante; el inbox no cancela un efecto
externo tardío.

DynamoDB conserva `expiresAt` en los registros en curso y agrega `ttl` solo cuando el procesamiento
termina. Completar requiere `expiresAt`; un registro sin ese atributo falla de forma cerrada. Los
registros en curso abandonados se monitorean y recuperan de forma explícita.

El horizonte de retención se mide desde la adquisición o toma de control exitosa, no desde la
finalización. La ventana de procesamiento soportada termina antes del plazo del lease, que es
estrictamente anterior a la retención. Un handler que excede esa ventana necesita idempotencia o
exclusión del participante y conciliación; no debe suponer que el inbox da efectos externos
exactamente una vez. Los argumentos de tiempo del inbox son horas confiables del servidor: los nodos
se sincronizan con NTP monitoreado y al elegir los leases se presupuesta el desfase máximo del reloj.
Un lease por sí solo no prueba que un handler suspendido o aislado haya terminado.

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
