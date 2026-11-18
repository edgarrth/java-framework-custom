# Integridad de auditoría

`axiz-core-audit` proporciona instantáneas inmutables, cálculo de sellos y verificación de
cadenas sin Spring ni proveedores de almacenamiento. El sellado se hace después de fijar los
resultados de la operación y de la transacción. Un evento sellado rechaza `withOutcomes`.
`withPersistence` conserva el sello porque ese campo describe la entrega, no el hecho auditado.

## Instantáneas

El constructor de `AuditEvent` convierte mapas, colecciones y arreglos en estructuras
inmutables. Conserva solo escalares admitidos; convierte fechas, UUID y caracteres a texto y
los enums por `name()`. Captura el valor de contadores atómicos. Un objeto desconocido se
sustituye por `<unsupported>` sin llamar a su `toString()`.

Los detalles admiten hasta 500 nodos, 32 KiB de JSON canónico, profundidad 6, claves de 128
caracteres y textos de 2000 caracteres. Se inspeccionan como máximo 500 miembros para acotar
el trabajo incluso cuando las claves son descartadas. `detailsSanitized` contabiliza
sustituciones y omisiones, incluidas las detectadas previamente por `ObjectMasker`.

Los límites corresponden al resultado final e incluyen nombres, escapes, comillas,
separadores y marcadores. No se truncan nombres de claves. Los ciclos producen `<cycle>`;
los límites de contenido producen `<truncated>`. El árbol construido no comparte listas,
mapas ni contadores mutables con la entrada.

## Sellos y estado

`AuditSealCalculator` calcula el siguiente sello sin estado ni I/O. `AuditSealer` lo combina
con `AuditChainState.advance`. En memoria, el bloqueo termina al guardar el sucesor; la entrega
ocurre después. En JDBC/R2DBC, la fila permanece bloqueada hasta terminar la transacción y
se escribe junto al outbox. El verificador usa la secuencia y admite llegada fuera de orden.

```java
var state = new InMemoryAuditChainState(Clock.systemUTC(), 16);
var sealer = new AuditSealer(
    "payments/sync/instance-1", AuditSealer.Mode.HASH, state, null, null);
AuditEvent sealed = sealer.seal(event);
```

Cada instancia y vía de entrega debe tener un identificador de cadena propio. El estado en
memoria no acredita que el destino haya guardado los eventos. Su cabeza puede estar por
delante de la entrega; no debe presentarse como un checkpoint durable.

En `HMAC` se exige un identificador de clave y un proveedor de buffers de 32 a 1024 bytes.
El proveedor transfiere un buffer nuevo en cada llamada y el calculador lo borra al terminar,
incluso ante un fallo. No debe devolver repetidamente el mismo arreglo. La clave queda fuera
del evento. `AuditChainVerifier` copia la clave que recibe y no modifica el arreglo del llamante.

El modo `HASH` detecta modificaciones parciales o accidentales. Quien pueda reescribir toda
la cadena puede recalcularla. `HMAC` permite detectar cambios mediante verificación mientras
la clave permanezca fuera del control de quien modifica el destino. Ningún modo impide el
borrado físico ni sustituye los controles del almacenamiento.

## Formato interoperable v1

Cada campo de la preimagen lleva una longitud de 4 bytes en orden big endian, seguida de
sus bytes. Los enteros de secuencia ocupan 8 bytes big endian. El texto usa UTF-8 estricto.
El hash se representa como 64 dígitos hexadecimales en minúscula. La cabeza inicial tiene
secuencia 0 y un hash de 64 ceros.

| Orden | Campo |
|---|---|
| 1 | Versión: un byte `1` |
| 2 | Modo: un byte `1` para SHA-256 o `2` para HMAC-SHA256 |
| 3 | Identificador de clave; texto vacío en HASH |
| 4 | Identificador de cadena |
| 5 | Secuencia |
| 6 | Hash anterior |
| 7 | Presencia de intento anterior: un byte `0` o `1` |
| 8–11 | Solo si existe: cadena, secuencia, hash y resultado del intento anterior |
| 12 | JSON canónico del evento, excluidos `integrity` y `persistence` |

El resultado del intento anterior ocupa un byte: `1` para `ROLLED_BACK`, `2` para `UNKNOWN`.
Cambiar o quitar cualquiera de estos datos invalida el sello. Un timeout no prueba rollback.
Los intentos de un mismo hecho comparten `id`; cada intento conserva su propia cadena,
secuencia y hash. Solo una retransmisión idéntica del mismo intento puede deduplicarse.

`JsonWriter.writeCanonical` implementa el formato Axiz v1, no RFC 8785:

- Claves ordenadas mediante `String.compareTo`; nombres siempre String.
- Escalares admitidos por clase exacta; números de punto flotante finitos. Float se amplía
  a double y ambos se escriben mediante `Double.toString`.
- BigDecimal con precisión hasta 64 y escala de −64 a 64, usando `toPlainString`;
  BigInteger con longitud de bits hasta 256.
- Escapes cortos para comillas, barra inversa, salto de línea, retorno, tabulación, retroceso
  y salto de página. Otros controles, U+2028, U+2029 y sustitutos aislados usan `\uXXXX`.
  Los pares válidos de sustitutos se escriben como su carácter Unicode.
- Los tipos desconocidos se rechazan; no se convierten por `toString`.

`CanonicalJsonTest` comprueba los contadores de bytes con 1000 cadenas Unicode reproducibles.
`AuditIntegrityTest` contrasta SHA-256 y HMAC con vectores calculados independientemente.

## Verificación

```java
var result = AuditChainVerifier.verify(receivedEvents, hmacKeyOrNull, trustedCheckpointOrNull);
```

Se proporciona una sola cadena por llamada. El verificador detecta `GAP`, `INVALID_HASH` y
`CONFLICT` y devuelve sus posiciones. También comprueba el contenido de las retransmisiones:
conservar un hash mientras se cambia el evento no permite eludir la verificación.

La completitud es un resultado independiente:

- `NOT_ASSESSED`: no hay checkpoint confiable o no se pudo demostrar su cobertura.
- `COMPLETE_UP_TO`: están los eventos hasta el checkpoint y su hash coincide.
- `TAIL_MISSING`: el checkpoint confirma una secuencia posterior a la última recibida.

Un prefijo válido sin checkpoint devuelve `NOT_ASSESSED`. Las métricas de secuencia no son
checkpoints. La confianza en la referencia depende de cómo se obtuvo y protegió; el objeto
Java por sí solo no demuestra su autenticidad.

## Transporte y lectura del registro

Use `AuditEventJson.write` y `AuditEventJson.read` para el documento auditado. El lector conserva
la representación de los números canónicos, incluidos `-0.0`, escalas decimales y exponentes.
Un lector JSON genérico puede convertirlos a otros tipos y cambiar los bytes que se verifican.
El codec rechaza campos desconocidos y una entrada que requiera sanitización con pérdida.

`EventEnvelope` contiene bytes ya codificados. Su formato estructurado utiliza `data_base64`
y mantiene `datacontenttype=application/json` cuando corresponde. Así preserva los bytes del
registro al pasar por SQS, SNS u outbox. Esta selección sigue la regla para valores de tipo
Binary del [formato JSON de CloudEvents 1.0.2, §3.1.1](https://github.com/cloudevents/spec/blob/v1.0.2/cloudevents/formats/json-format.md#311-payload-serialization).
Los consumidores siguen pudiendo recibir eventos externos con `data`; una entrada que combine
`data` y `data_base64` se rechaza. Base64 no es cifrado: `data_base64` se oculta por completo
cuando pasa por el enmascarador de logs o auditoría.

El `id` dentro del registro identifica el hecho. Para eventos sellados, el `id` de CloudEvents,
FIFO e inbox usa `deliveryId()`: un digest de cadena, secuencia y hash. Dos intentos del mismo
hecho tienen identidades de entrega diferentes y ambos se conservan. La retransmisión del mismo
intento mantiene su identidad. Cambiar solo `persistence` no cambia esa identidad.

## Configuración Spring y entrega transaccional

La integridad predeterminada es `HASH`. `NONE` solo se permite con
`axiz.config.environment=local`, `test` o `ci`. Cada instancia genera una cadena por vía con
formato servicio/vía/UUID; no se reutiliza una cadena en memoria tras un reinicio.

Para autenticar la evidencia con una clave independiente:

```yaml
axiz:
  audit:
    mode: DURABLE
    integrity:
      mode: HMAC
      hmac-secret: audit/integrity-key
      hmac-key-id: audit-2026-01
  outbox:
    store: jdbc
    audit-chain-table: axiz_audit_chain
    audit-statement-timeout: 2s
```

`hmac-secret` es el nombre resuelto por `SecretResolver`, no el valor. Su contenido codifica
32 a 1024 bytes en Base64. La clave se resuelve una vez al construir los publicadores,
se cierra el `SecretValue` recibido y se conserva una copia propia que se borra al cerrar el
contexto. No se consulta al gestor de secretos desde el hilo reactivo de cada petición. Para
rotarla, despliegue nuevas instancias con otro `hmac-key-id` y conserve las claves anteriores
en el verificador según la retención de evidencia. No reutilice claves de cifrado o índices.

DURABLE exige la integración `outbox-jdbc` o `outbox-r2dbc`, su gestor transaccional y el puerto
de estado correspondiente. El esquema canónico de outbox incluye `axiz_audit_chain`; en
producción debe aplicarse mediante migraciones. Una tabla personalizada requiere su migración
correspondiente. El framework compara por identidad el recurso de outbox y el de la cadena;
una extensión que no declare esa identidad no puede afirmar esta atomicidad.

Los éxitos se registran en `beforeCommit`, lo más tarde posible. El rollback revierte también
la secuencia. Los fallos de negocio se guardan en una transacción independiente, con límite de
concurrencia y plazo. La degradación conserva una instantánea nueva del mismo hecho:

| Evidencia del intento durable | Resultado de degradación |
|---|---|
| Commit confirmado | No se crea otro intento |
| Rollback confirmado tras sellar | Referencia `ROLLED_BACK`, persistencia `NOT_CONFIRMED` |
| Intento sellado sin confirmación, incluido timeout | Referencia `UNKNOWN`, persistencia `UNCERTAIN` |
| No llegó a sellarse | Nuevo sello sin inventar una referencia previa |

La cancelación no demuestra rollback. Si el commit acaba después del plazo, deben conservarse
ambos intentos. Una secuencia reutilizada tras rollback solo coincide con el intento anterior
si también coinciden el hash y el id del hecho.

`checkpoint` obtiene una conexión independiente y lee solo el estado confirmado. No debe
invocarse con un proxy que devuelva la conexión de negocio ni con un pool sin capacidad para
otra conexión. Las conexiones, tiempos de adquisición del pool y permisos de acceso son parte
de la configuración del despliegue. El límite de consulta no sustituye el límite del pool.

El estado reactivo se expone mediante `ReactiveAuditChainState`, en `axiz-kit-audit-reactor`;
no introduce un driver en el núcleo ni un puente bloqueante hacia `CompletionStage`. Los
publicadores decorados conservan cierre y métricas de cola y confirmación.

## Vía estricta

`StrictAuditPublisher.publish` retorna solo después de confirmar la entrega durable y propaga
los fallos. `AsyncStrictAuditPublisher.publish` ofrece el mismo contrato mediante un
`CompletionStage<Void>`; el llamante debe esperar su terminación antes de revelar un dato
protegido o afirmar que la operación fue auditada. Una aceptación en memoria no es un acuse.

```yaml
axiz:
  audit:
    strict:
      enabled: true
      mode: DIRECT
      timeout: 2s
      concurrency: 4
      checkpoint-interval: 1m
```

`DIRECT` requiere de uno a dieciséis destinos explícitos `StrictAuditSink` o
`AsyncStrictAuditSink`. Las integraciones `audit-kafka` y `audit-sqs` los proporcionan.
Kafka usa un productor propio con `acks=all` e idempotencia, sin modificar el productor
general de la aplicación; SQS exige un identificador de mensaje en la respuesta. El despliegue
debe configurar la replicación, retención y permisos del destino según su garantía requerida.
Si hay varios destinos, todos deben confirmar. La entrega parcial puede existir después de
un fallo; este resultado no autoriza a devolver éxito ni a asumir que no se escribió nada.

Un `AuditSink` genérico o el log no califican automáticamente como destino estricto. Una
extensión que registre un sink estricto recibe el evento ya sellado y debe cumplir el contrato
de durabilidad de ese puerto. También puede aportar directamente el publicador estricto; la
autoconfiguración respeta ese bean. El modo `NONE` no satisface la vía estricta de Axiz.

`DURABLE` usa el mismo estado SQL y outbox, pero abre una transacción independiente y confirma
después de su commit. Puede coexistir con `audit.mode=ASYNC`. Requiere el proveedor outbox
JDBC o R2DBC, una ruta de relay válida y recursos transaccionales coherentes; desactivar
explícitamente el relay implica hacerse cargo de la publicación posterior. Un rollback del
llamante no deshace esta evidencia. La implementación reactiva usa el operador transaccional
nativo y no transporta una transacción del llamante dentro del `CompletionStage`.

El plazo estricto acepta valores positivos hasta un minuto y la concurrencia va de 1 a 64.
La saturación rechaza; no ejecuta I/O en el hilo del llamante. Si vence el plazo tras aceptar
un envío, el trabajo pendiente conserva su capacidad hasta terminar. No hay degradación a
cola o log que convierta el fallo en éxito. Un timeout o una cancelación pueden dejar un
registro durable y deben tratarse como ausencia de confirmación, no como prueba de rollback.

## Checkpoints periódicos de cadenas en memoria

Cuando existe un publicador estricto del modelo de ejecución seleccionado, Spring publica
eventos `audit.chain.checkpoint`. También lo hace con un puerto estricto aportado por la
aplicación aunque `strict.enabled=false`, que solo desactiva la implementación estricta de
Axiz. Sin vía estricta, o con integridad desactivada, no se inicia el temporizador.

Los detalles contienen `chainId`, `sequence`, `hash` y `at`. Cada ciclo captura las cabezas
en memoria, omite cadenas vacías o ya confirmadas y envía las nuevas referencias por la vía
estricta. El intervalo predeterminado es un minuto y admite de un segundo a un día. Los ciclos
no se solapan. El apagado cancela el temporizador y deja de aceptar acuses del ciclo anterior.
El componente limita también una implementación estricta personalizada que no termine su
envío: un trabajador y una posición de relevo, como máximo dos envíos pendientes tras timeout.

El verificador obtiene el checkpoint del destino durable confiable y comprueba su sello y
origen antes de usarlo. Una instantánea local o un contador no bastan. Si el checkpoint llega
antes que los eventos de la cola, `TAIL_MISSING` refleja esa falta hasta recibirlos. Lo posterior
al último checkpoint confirmado sigue sin estar acreditado. La cadena estricta en memoria
puede publicar su propio checkpoint, que cubre su cabeza anterior al envío de ese checkpoint.

`axiz.audit.checkpoint.confirmed` y `axiz.audit.checkpoint.failed` son contadores operativos;
no sustituyen estos registros. Los fallos se registran por clase de excepción, sin su mensaje
ni los detalles del evento.

## Pruebas

Desde `axiz-framework`, las siguientes selecciones ejecutan las comprobaciones de sellado,
checkpoints y fronteras transaccionales; la segunda requiere Docker:

```bash
mvn -Pquality -pl spring/axiz-audit-spring-boot-autoconfigure -am \
  -Dtest=AuditIntegrityTest,AuditMemoryCheckpointsTest,AuditIntegrityWiringTest,StrictAuditWiringTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
mvn -Pquality -pl quality/axiz-store-integration-tests -am \
  -Dtest=StrictJdbcAuditTest,PostgresAuditBoundaryIT,PostgresReactiveUseCaseIT \
  -Daxiz.it.requireDocker=true -Dsurefire.failIfNoSpecifiedTests=false test
```

Estas selecciones son pruebas focalizadas. El cierre de calidad del proyecto requiere además
`verify`, las reglas de arquitectura y las comprobaciones de los consumidores generados.
