<!-- generated -->
# axiz-audit-spring-boot-autoconfigure

`axiz.audit.mode=ASYNC|DURABLE|SYNC` es el único selector de entrega; los selectores booleanos son
configuración inválida. Los publicadores propios del framework requieren un modo explícito fuera de local/test.
DURABLE requiere un outbox compatible, un recurso transaccional y un destino del relay; una instancia
solo de escritura desactiva explícitamente su relay local. Los tópicos Kafka o URLs de colas SQS
configurados vacíos hacen fallar el arranque. El soporte de rutas valida el direccionamiento, no la
existencia ni los permisos del recurso remoto.

@Auditable, eventos de auditoría saneados y publicadores asíncronos (log, MSK, SQS) (autoconfiguración).

## Dependencia

```xml
<dependency>
  <groupId>pe.axiz</groupId>
  <artifactId>axiz-audit-spring-boot-autoconfigure</artifactId>
</dependency>
```

La versión la gestiona `axiz-bom` (los servicios heredan `axiz-parent-service`).

## Tipos principales

| Tipo | Propósito |
|---|---|
| `AuditContextResolver` | Construye el contexto de auditoría (actor, correlación, canal, tenant) a partir de la petición actual. |
| `AuditOutsideTransaction` | Qué hace la auditoría durable con una acción exitosa registrada fuera de una transacción. |
| `AuditSink` | Destino de eventos de auditoría aportado por la autoconfiguración de un adaptador. |
| `AuditableInterceptor` | Aplica `Auditable`: registra quién hizo qué, sobre qué recurso y con qué resultado. |
| `AxizAuditAutoConfiguration` | Auditoría: `Auditable` mediante un advisor de infraestructura, eventos inmutables saneados, publicación asíncrona real (cola acotada, hilo virtual, respaldo en log) y auditoría HTTP opcional. |
| `AxizAuditProperties` | Configuración de auditoría (`axiz.audit.*`). |
| `HttpAuditFilter` | Audita las peticiones HTTP que modifican estado. |
| `HttpAuditWebFilter` | Equivalente reactivo de `HttpAuditFilter`. |
| `TransactionalAuditPublisher` | Auditoría durable: una acción exitosa registrada dentro de una transacción se escribe en el outbox en esa misma transacción, de modo que la evidencia existe si y solo si el cambio de negocio se confirmó, y sobrevive a una caída justo después del commit (el relay la publica luego, al menos una vez, con el id del evento para deduplicar). |

## Auditoría reactiva nativa

Las aplicaciones reactivas exponen `ReactiveAuditTrail` y `ReactiveAuditPublisher`, respaldados por
`AsyncAuditEventPublisher`/`AsyncAuditSink`. SQS usa `SqsAsyncClient`, recursos AWS propios del arranque
y cancelación nativa exacta. Los sinks imperativos se rechazan; en aplicaciones reactivas la auditoría
Kafka usa el límite dedicado de envío asíncrono acotado que se describe más abajo.
El interruptor global `axiz.audit.enabled=false` también desactiva el cableado nativo.

`@Auditable` admite métodos Mono/Flux en aplicaciones reactivas. Cada suscripción es dueña de su
registro y de los metadatos de la petición. El contexto transaccional nativo de R2DBC se conserva durante toda la publicación.

## Modos de entrega y evidencia transaccional

`axiz.audit.mode` se configura explícitamente con `async`, `durable` o `sync`. Los selectores
booleanos `durable.enabled` y `async.enabled` se rechazan. El starter del proveedor Kafka o SQS se
selecciona por separado; este módulo neutral no es dueño de esos SDK ni de sus autoconfiguraciones.

| Modo | Garantía |
|---|---|
| `async` (implícito solo en desarrollo) | Cola acotada en memoria. Encolar no equivale a una confirmación del destino y no sobrevive a una caída del proceso. Las aplicaciones desplegadas deben elegir un modo explícitamente. |
| `durable` | El éxito se agrega al outbox en la transacción de negocio. La entrega del relay es al menos una vez; los consumidores deduplican los IDs de evento. |
| `sync` | Espera la confirmación del destino, con el transporte asíncrono nativo en WebFlux. No es atómico con los cambios de negocio. Un fallo de entrega registra en el log el evento saneado como `audit.persistence=UNCONFIRMED` y no deshace la finalización del negocio. |

El advice de auditoría se ejecuta con orden **20**, fuera del advice de la transacción de negocio. Un
advisor interno adicional agrega los éxitos durables antes del commit. El arranque verifica el orden real
de los proxies, incluido el advisor transaccional de Spring; cambiarlo no puede eliminar en silencio la evidencia atómica.

Un fallo durable se registra **después** del rollback en una transacción corta independiente. Si una
transacción externa sigue activa, el evento espera a que termine el límite externo de auditoría, HTTP o
mensajería. Los límites HTTP requieren `axiz.audit.http.enabled=true`; los límites de despacho del broker
se registran automáticamente. Una llamada sin ninguno de estos límites usa `REQUIRES_NEW` acotado como
último recurso. El trabajo asíncrono servlet imperativo debe establecer su propio límite de transacción y
auditoría en el hilo de ejecución; las transacciones ligadas al hilo no se propagan a tareas asíncronas.

Ambas colas asíncronas toman una instantánea de los metadatos de la petición y de la observación padre al
encolar. El worker restaura solo esos valores para la entrega; se excluyen los accesores de transacción
registrados. Los scopes de contexto se cierran después de cada evento, de modo que una petición no puede
filtrar sus metadatos a otra.

`axiz.audit.durable.failure-timeout` (por defecto `2s`) acota la persistencia de fallos, incluida la
obtención de la conexión. `failure-concurrency` (por defecto `4`) limita las escrituras concurrentes de
fallos. JDBC usa workers virtuales propios; R2DBC compone un `TransactionalOperator` independiente sin
bloquear. El buffer pendiente se limita a 1.024 eventos por límite externo; el desborde usa el último recurso acotado.

Si la persistencia del fallo no puede completarse, se conserva el error de negocio original y el evento
saneado se encola con `audit.persistence=NOT_CONFIRMED`. Esto es degradación, no evidencia durable.
No se cuenta como `lost` mientras la cola lo retiene. Las métricas `axiz.audit.persistence.not.confirmed`
y `axiz.audit.persistence.unconfirmed` exponen estas dos condiciones. Las métricas de salud de la cola
(`pending`, `lost`, `fallback`, `failures`, `worker.alive`) aplican a ambas pilas.

Los eventos llevan dos ejes separados: `transactionOutcome` registra el commit o rollback local confirmado,
o `UNKNOWN`; `operationOutcome` permanece en `UNKNOWN` ante un fallo, salvo que `RejectedBeforeEffect`
certifique explícitamente que no hubo efectos. Un rollback local no prueba que una integración externa
no haya tenido efecto. Los fallos de conexión durante el commit y los fallos transaccionales no
clasificables permanecen en `UNKNOWN`.

Fuera de una transacción, el éxito durable obedece a `axiz.audit.durable.outside-transaction`: `fail`
rechaza la publicación y `async` usa el respaldo. Para este último no se declara ninguna garantía durable.

Los charts de Helm generados contienen reglas opcionales de Prometheus para auditoría no confirmada,
degradada o perdida y para agregados de outbox bloqueados. Se habilitan solo con el CRD de Prometheus
Operator instalado y el scraping del servicio configurado. `monitoring.prometheusRules.metricSelector`
se adapta a las etiquetas reales del destino.

## Pruebas

Las pruebas unitarias están en `src/test/java` y documentan el comportamiento esperado; se ejecutan con `./mvnw -pl spring/axiz-audit-spring-boot-autoconfigure verify`.

## Integridad y publicación confirmada

`axiz.audit.integrity.mode=HASH` sella por defecto los eventos inmutables canónicos; `HMAC` agrega
autenticación con la referencia de secreto separada `integrity.hmac-secret` y el ID de rotación
`integrity.hmac-key-id`. `NONE` desactiva el sellado y es incompatible con la publicación estricta.
Una cadena de hash sin clave no impide que un actor con acceso de escritura recalcule la evidencia.

`axiz.audit.strict.enabled=true` publica `StrictAuditPublisher` o su equivalente asíncrono nativo.
`strict.mode=DIRECT` requiere un sink durable atestado explícitamente; `DURABLE` requiere un outbox
confirmado por separado. En esta ruta no existe respaldo tolerante en cola o log. El timeout (por
defecto 2 s) y la concurrencia (por defecto 4) están acotados. Un fallo de publicación se propaga;
no afirma que un efecto de negocio confirmado previamente se haya revertido.

Las cadenas en memoria emiten checkpoints confirmados cada `strict.checkpoint-interval` (por defecto
1 minuto) cuando existe una ruta estricta. El verificador debe obtener un checkpoint confiable del sink
durable, no una instantánea de diagnóstico local. `AuditChainVerifier` informa la integridad por separado
de la completitud: sin un checkpoint confiable, la completitud es `NOT_ASSESSED`; un sufijo faltante solo
es detectable hasta ese checkpoint. Los checkpoints y las claves de verificación HMAC se conservan bajo
un control operativo separado. Ningún hash local, métrica o encolado exitoso prueba la recepción completa.

### Envío a Kafka en aplicaciones reactivas

La publicación servlet conserva su adaptador síncrono. En aplicaciones reactivas el framework invoca
`KafkaTemplate.send()` en workers dedicados, porque la búsqueda de metadatos o un buffer del productor
lleno pueden esperar dentro de `send()`. Completar significa confirmación del broker; esa confirmación no
se espera en un worker ni en un hilo de Reactor. Se mantiene el cliente Kafka estándar.

`axiz.audit.kafka.send-workers` vale 2 por defecto (rango de 1 a 64) y `send-queue-capacity`, 256
(rango de 0 a 65536). Las propiedades tipadas validan estos límites antes de crear el despachador. La
admisión se acota entre el trabajo encolado y los envíos sin confirmar; al agotarse falla de inmediato en
lugar de ejecutarse en el llamador. El timeout de Kafka configurado acota el envío más la confirmación y
`max.block.ms`. Las métricas `axiz.transport.pending`, `.queued`, `.rejected` y `.latency` llevan el tag
`transport` (`kafka-events` o `kafka-audit`). Los productores y workers se cierran con el contexto.

Cancelar trabajo encolado impide su envío. Una vez que Kafka aceptó un envío, la cancelación o el
timeout no pueden retirarlo: es posible una confirmación tardía, y la capacidad sigue reservada hasta que
el transporte termina. Un reintento debe conservar el ID del evento y usar la deduplicación del inbox del consumidor.
