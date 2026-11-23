<!-- generated -->
# axiz-core-audit

Eventos de auditoría inmutables, políticas de publicación, saneamiento y publicadores asíncronos.

## Dependencia

```xml
<dependency>
  <groupId>pe.axiz</groupId>
  <artifactId>axiz-core-audit</artifactId>
</dependency>
```

La versión la gestiona `axiz-bom` (los servicios heredan `axiz-parent-service`).

## Tipos principales

| Tipo | Propósito |
|---|---|
| `AsyncAuditPublisher` | Entrega imperativa en segundo plano: cola acotada que vacía un hilo virtual; atiende a llamadores servlet o workers. |
| `AsyncAuditEventPublisher` | Puerto de aceptación nativo que devuelve un `CompletionStage<Void>` cancelable; no espera I/O al enviar. |
| `AsyncAuditTrail` | Registros asíncronos saneados con la misma política de formato que `AuditTrail`. |
| `AuditRecord` | Construcción compartida, solo de CPU, de instantáneas de eventos inmutables y saneadas. |
| `CompositeAsyncAuditPublisher` | Difusión nativa: se intenta cada destino, se informan los fallos y la cancelación cancela el envío en curso. |
| `LoggingAsyncAuditPublisher` | Entrega a un appender no bloqueante; completar confirma la entrega al log, no un almacenamiento durable. |
| `AuditEvent` | Registro de auditoría inmutable de quién hizo qué, sobre qué recurso, cuándo y con qué resultado. |
| `AuditPolicy` | Cuándo se registra una acción auditada. |
| `AuditPublishException` | Un evento de auditoría no pudo entregarse a su destino. |
| `AuditPublisher` | Puerto para publicar eventos de auditoría (log, MSK, SQS, etc.). |
| `AuditTrail` | Construye eventos de auditoría saneados y los entrega al publicador. |
| `Auditable` | Audita un método: actor, acción, recurso, resultado y argumentos y retorno saneados. |
| `CompositeAuditPublisher` | Publica en varios publicadores; el fallo de uno no impide los demás. |
| `LoggingAuditPublisher` | Escribe los eventos de auditoría como líneas JSON en el logger `axiz.audit`. |

## Entrega nativa

Las integraciones reactivas seleccionan `AsyncAuditEventPublisher`. En modo ASYNC,
`QueuedAsyncAuditPublisher` admite eventos sin esperar y delega la espera de la confirmación en su
propio drenador; los hilos de las peticiones no esperan I/O de red. Cada builder pertenece a una
sola operación. Cada evento recibe una instantánea inmutable de los detalles saneados; los cambios
posteriores del builder no alteran un evento entregado. El transporte nativo sigue perteneciendo a
la aplicación que lo crea. Para evidencia transaccional se usa la auditoría durable reactiva de
Spring con el outbox R2DBC dentro de la transacción de negocio. El log requiere un appender no
bloqueante y sigue siendo de mejor esfuerzo.

## Pruebas

Las pruebas unitarias están en `src/test/java` y documentan el comportamiento esperado; se ejecutan con `./mvnw -pl core/axiz-core-audit verify`.

## Política de entrega explícita en Spring

Los publicadores del framework exigen `axiz.audit.mode=ASYNC|DURABLE|SYNC` fuera de `local`/`test`.
Los selectores booleanos se rechazan, también junto a un modo canónico. La auditoría desactivada y
los publicadores propios de la aplicación mantienen su propia política de entrega. ASYNC es el
valor por defecto en desarrollo y usa memoria, no persistencia resistente a caídas. El generador
escribe DURABLE cuando hay una capacidad de outbox efectiva, si no ASYNC, y conserva las elecciones
existentes al agregar capacidades.

`axiz.audit.async.delivery-timeout` (10 s por defecto) acota cada entrega imperativa y nativa.
Para un destino imperativo, el drenador de la cola espera a un único worker de entrega propio con
admisión acotada; una entrega que vence se cancela y sigue la política de fallo y respaldo vigente.
La entrega nativa de la cola invoca el contrato de envío no bloqueante en su propio drenador virtual
y espera un único plazo de confirmación. La cancelación llega a esa etapa antes del respaldo, lo que
evita dos temporizadores en competencia. Esta ruta nativa interna no expone un constructor
imperativo sin validar. El delegado debe cooperar con la interrupción y configurar los timeouts de
su transporte: Java no puede detener por la fuerza a un delegado que ignora la cancelación, y el
respaldo puede duplicar una entrega tardía. El apagado reparte su presupuesto total de 10 segundos
entre el drenado, la cancelación, el respaldo y la terminación del worker. `Shutdown.terminated`
informa de forma explícita los callbacks que no cooperan, y `unconfirmed` distingue una aceptación
incierta del respaldo de las pérdidas confirmadas. Los publicadores cerrados rechazan eventos nuevos.
Admisión en la cola, entrega y apagado son límites distintos; ninguno vuelve durable a ASYNC. Las
instantáneas de contexto se abren en el worker que entrega; el estado de la transacción de negocio
nunca se transfiere entre hilos.

DURABLE se une a la transacción de negocio solo con el mismo gestor de transacciones y recurso, y
con un destino y relay de outbox configurados. Un éxito fuera de una transacción usa la entrega
asíncrona por defecto; `axiz.audit.durable.outside-transaction=fail` lo rechaza. Es una política
del consumidor, no un valor por defecto.
