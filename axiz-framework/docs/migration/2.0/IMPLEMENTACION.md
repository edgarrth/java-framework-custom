
Estado: **en implementación**, actualizado el 2 de noviembre de 2026. Este registro no declara
terminada ni publicada la versión 2.0. La referencia normativa es el

Se preservan las correcciones previas de precisión monetaria, identidad y reproducibilidad.

| Etapa | Estado y evidencia |
|---|---|
| Capas, nombres, parents y coordenadas 2.0 | Reactor de 159 módulos; el control de nombres, parents, pertenencia al reactor y excepción cerrada R2DBC pasa |
| Eventos como capacidad | Contratos y handlers trasladados a core/Spring messaging; EDA conserva la coreografía; validación global aprobada |
| Estructuras, extensiones y reglas por arquetipo/stack | API fluida y catálogo implementados; catálogo, aislamiento y rechazo de paquetes vacíos aprobados en el reactor completo |
| Transacción hexagonal reactiva | PostgreSQL real con BlockHound verifica commit, rollback por error/cancelación, suscripciones repetidas y reconexión SCRAM; integración global aprobada |
| Catálogo schema 2 y herramientas | CLI e Initializr prueban el catálogo nuevo; upgrade acepta la decisión core/gateway y el renombre; quedan verificaciones de migración por completar |
| Recetas OpenRewrite | 26 pruebas y perfil quality aprobados; handlers Mono/Flux, seis estructuras/extensión, scopes y entidades JPA/R2DBC/repositorios DynamoDB compilados contra las APIs reales 2.0 |
| Build/deploy y candidatas | Los cuatro workflows nuevos están implementados y validados localmente; 44 regresiones y round trip OCI real. El framework ya tiene ci/deploy independientes. Framework, tools, demo y plantillas usan contratos separados; los cuatro exports anteriores se retiraron. Faltan snapshots, automatización independiente y aceptación remota |
| Demo y gateway independiente | Pendiente la migración de los nueve servicios y la comprobación de convivencia |
| Documentación, compatibilidad y aceptación final | Japicmp contra 1.0.0 e inventario procesable comprobados; sitio de documentación reorganizado y build estricto aprobado. Faltan actualización completa de ejemplos, muestras y aceptación global |

Las evidencias se guardan fuera del repositorio en `/tmp/axiz2-implementation`.
`source-before.zip` y `before.patch` conservan el estado previo, incluidos cambios sin commit.
La publicación remota se mantiene separada de la implementación y de las pruebas locales.

## Comprobaciones realizadas

Entorno: Fedora Remix 42 sobre WSL2, JDK 25.

- La compilación completa inicial del reactor reorganizado pasó; Maven Enforcer y la gobernanza de
  dependencias pasan. La comprobación estructural identifica exactamente los tres módulos con
  permiso de uso directo de Reactor en tecnología.
- Las 100 pruebas del generador y quality pasaron, incluidas preservación de identidades, migración
  schema 1/2, extensiones, combinaciones del catálogo y snapshot AsyncAPI idéntico al contrato
  utilizado al generar. Los proyectos sin AsyncAPI no reciben un snapshot ficticio.
- CLI e Initializr pasaron sus suites funcionales y de calidad. El CLI tiene 45 pruebas; upgrade
  ejecuta doctor también con la versión actual o con recetas omitidas, y ofrece `--extension`.
- Los fixtures de migración de handlers conservan `Publisher<Void> on(...)`, `react(...)` y el
  wrapper transaccional. El resultado se compila contra las clases reales de axiz 2.0.
- Las pruebas hexagonales con PostgreSQL real y BlockHound verificaron que negocio y outbox
  confirman o revierten juntos, también al cancelar y cambiar de hilo.
- Las validaciones completas descubrieron carencias de cobertura, una condición de carrera real en
  la métrica de pendientes y una prueba que observaba el envío antes del commit del relay. Se
  corrigieron sin reducir umbrales. La validación global finalizó con éxito en los 159 módulos:
  1.242 pruebas Surefire y 41 integraciones Failsafe, sin fallos, errores ni omisiones
  (`quality-verify7.log`, anterior a la API de instrucciones de carga firmada). Incluyó
  arquitectura y cobertura agregada; exigió Docker en las integraciones. Esta ejecución reutilizó
  la mutación ya comprobada; no se presenta como una nueva ejecución completa de PIT.
- PIT completó los módulos ejecutables. Se inspeccionaron 63 reportes: 4.437 mutantes detectados,
  43 timeouts, 797 supervivientes y 306 sin cobertura; no hay errores de procesos de mutación. La
  ejecución global encontró que el API reservado de EDA contiene solo metadatos y no tiene código
  para mutar: se documentó esa excepción específica. Los controles posteriores de arquitectura,
  agregación de cobertura e integraciones terminaron correctamente en la validación global.
- El módulo outbox pasó sus 12 pruebas y quality después de sincronizar la prueba con el envío y
  el commit. Las dos pruebas de payloads verifican evolución aditiva en ambos sentidos y que el
  mapper HTTP conserve su validación estricta.
- Las 185 clases con rupturas de API previstas están inventariadas con fingerprints exactos y
  motivos. Las 161 reubicaciones conservan sus firmas públicas/protegidas tras aplicar los mapeos.
  El control falla si faltan reportes, cambia una API de reemplazo o aparece una ruptura adicional.
  Se probaron también una línea base inexistente y el rechazo del perfil normal a una ruptura.
- Los contratos de build/deploy y sus verificadores tienen 44 pruebas; el aislamiento Maven añade tres.
  Incluyen corrupción de bytes, identidad externa del manifiesto, parámetros, tipos de producto,
  digests por plataforma, repositorios locales independientes, preflight de todos los archivos,
  rechazo de conflictos antes de escribir, reanudación de promoción y conservación idempotente de
  assets. Se añadieron values por commit, registros firmados, rechazo de predecesores obsoletos,
  recuperación ante un push concurrente a Git y preservación de versiones externas al reactor.
  Las pruebas de promoción usan un servidor HTTP y un Git bare locales; no escriben en Nexus ni
  GitHub reales. Las 17 regresiones de política de workflows también pasan.
- Docker buildx y Helm reales verificaron una imagen amd64, un índice amd64/arm64 y un chart en
  un registro efímero accesible solo por loopback. Los hashes recuperados coinciden y las
  plataformas distintas producen índices y hashes de parámetros distintos. Son imágenes de
  fixture: esta comprobación no acredita todavía servicios JVM/native completos.
- Actionlint valida los workflows nuevos y el consumidor del framework. Kubeconform aprobó los
  tres recursos de referencia de Argo CD/Kyverno, sin omisiones. Argo usa chart OCI por digest y
  values Git por SHA; Kyverno y el build comparten la identidad keyless y el builder SLSA.
  La política no se ha ejecutado contra un clúster real.
- MkDocs estricto aprobó el sitio reorganizado: cuatro capas, cinco estructuras y CQRS como
  extensión, catálogo schema 2, reglas fluidas y ayuda del CLI 2.0 generada desde el JAR real.
- Se incorporó `./mvnw --bootstrap` para instalar los dos BOMs independientes antes de leer el
  modelo raíz. Sus regresiones verifican checksums y conservación de argumentos con espacios.
  La compilación aislada desde una copia sin targets terminó correctamente con quality y un
  repositorio Maven propio, inicialmente vacío; no usó los artefactos privados de `~/.m2`.
- El consumidor del framework separa `ci.yaml` y `deploy.yaml`. La ejecución productora debe
  terminar completamente antes de promover; wrapper, muestras y compatibilidad no son gates
  opcionales. Cuatro regresiones verifican esta separación y forman parte de las 17 pruebas
  Python del framework, todas aprobadas. Release-please prepara el draft y crea el tag; el deploy
  conserva el manifiesto firmado antes de publicar el draft.

## Continuación: secretos y contexto asíncrono

Secretos ya ofrece el puerto JDK `AsyncSecretResolver`, composición que solo continúa ante
ausencia, caché remota acotada con coalescencia y cancelación nativa, snapshot de entorno y
`RefreshingFileSecretResolver`. Los archivos se leen al arrancar y en un trabajador propio;
las peticiones consultan memoria. Se comprueban límites de tamaño, UTF-8 y rotación, y un fallo
de refresco cierra el acceso hasta recuperarse. El cierre cancela la tarea y libera sus recursos.

`SecretsManagerAsyncResolver` y `ParameterStoreAsyncResolver` usan los clientes nativos. La
selección `nombre#campo` se comparte con las implementaciones síncronas; archivos y SSM
permiten sus correspondientes aliases. El JSON inválido produce un diagnóstico sin valores
sensibles y no provoca fallback. Los campos estructurados conservan JSON válido. Spring ofrece
`ReactiveSecrets`, distingue el resolver de bootstrap, permite reemplazar el puerto nativo y
respeta `axiz.secrets.enabled=false` antes de inicializar clientes. Solo cachea las fuentes
remotas: el snapshot de archivos no adquiere otro TTL de obsolescencia.

Aprobaron 11 pruebas del core, 7 del adaptador y 13 de Spring, con calidad, cobertura y análisis
estático (`secrets-native-quality5.log`, `secrets-native-adapter-quality6.log`,
`secrets-native-spring-quality3.log`). La mutación del adaptador, el transporte y las reglas
reactivas aprobó en `secrets-observation-final-pit1.log`. Las reglas rechazan el uso de los
puertos síncronos de secretos y cifrado en flujos reactivos, conservando el uso de sus DTO.

La aceptación real con LocalStack y BlockHound aprobó cuatro integraciones de secretos:
snapshot sin releer el archivo borrado, 40 consultas que comparten una petición, rotación,
renovación de credenciales, reconexión, SecureString, prioridad, ausencia y corrupción del JSON.
Comprueba además el padre de las observaciones de Secrets Manager y el fallback a SSM, junto
con contextos independientes de dos suscriptores que comparten la misma carga
(`native-context-secrets-lock-integration1.log`). No se detectaron sitios originales de bloqueo
ni errores de transporte, scopes o schedulers.

`Stages` incorpora una SPI acotada al scope de invocación para capturar callbacks en Java puro.
`ReactiveStages` conecta el contexto del suscriptor con la llamada nativa, sus señales, demanda
y cancelación, sin hooks globales. Las cachés compartidas conservan el contexto de cada llamador.
Foundation aprobó 22 pruebas, incluidas seis regresiones y una JVM sin context-propagation,
con cobertura y SpotBugs sin hallazgos (`stages-context-foundation4.log`). Las fachadas de once
módulos aprobaron 183 pruebas y sus controles en `stages-context-facades-quality1.log`.
El refuerzo de cancelación ante errores de captura y la paginación S3 aprobaron 54 pruebas de
commons y 14 de S3, con calidad y mutación (`stages-context-cancellation-quality1.log`): 454
mutantes en commons y 153 en S3. La paginación conserva el contexto al iniciar la siguiente
petición desde otro hilo; un error de contexto cancela el I/O pendiente.

Durante esa comprobación se detectó un error previo de instrumentación tardía de BlockHound:
una prueba anterior podía crear el scheduler antes de instalar el agente y provocar una
`IllegalMonitorStateException` en un hilo ocioso. El toolkit ahora descubre el opt-in del plan
JUnit y lo instala antes de ejecutar las pruebas. Los planes servlet no se instrumentan.
Dos JVM separadas prueban ambas condiciones; las 16 pruebas del toolkit y sus controles pasan
(`blockhound-early-startup-quality2.log`). Las 16 pruebas de locks se repitieron con la corrección
y sin esa excepción en `native-context-secrets-lock-integration1.log`.

El interceptor AWS usa ahora `SenderContext` de tipo CLIENT e incorpora las cabeceras de
trazabilidad antes de SigV4. Detecta el tipo de cliente que informa el SDK: no deja un scope
abierto entre hilos asíncronos, incluso si el interceptor se registra directamente en un cliente
externo. `javap` comprobó que se conservan los miembros anteriores y solo se añade
`modifyHttpRequest` (`aws-tracing-api-review.json`); el inventario de su reemplazo refleja
esa adición tras regenerar japicmp.

La suite completa descubrió contención real en el pool compartido de hashes del SDK durante
KMS; corresponde al [problema 6590 de AWS](https://github.com/aws/aws-sdk-java-v2/issues/6590).
Para DynamoDB, KMS, Secrets Manager, SSM, SQS y SNS se selecciona el firmante SigV4 estándar
del mismo SDK, que usa SHA-256 por hilo para sus payloads en memoria. S3 mantiene su firmante
de streaming. Se conservan la firma del payload y los algoritmos; no se añadieron excepciones
de BlockHound para el pool afectado. Transporte aprobó 19 pruebas y calidad/mutación
(`aws-nonblocking-sigv4-quality1.log`, `aws-nonblocking-sigv4-tests2.log`).

`native-context-acceptance2.log` aprobó las 63 integraciones completas de PostgreSQL, Valkey,
DynamoDB, S3, KMS y secretos, sin fallos, errores ni omisiones. El HTTPS real confirma dos
conexiones en frío con el truststore ya borrado, rechazo de certificado/hostname y presencia
de `traceparent` tanto en la red como en la lista de cabeceras firmadas con SigV4.
Las 49 pruebas de arquitectura y las 22 de Foundation aprobaron en la ejecución anterior
(`native-context-acceptance1.log`); esa ejecución encontró el defecto de hashes que motivó la
corrección, por lo que su reactor completo no se considera aprobado.

## Continuación: secuencias y transporte nativo

KMS dispone de `AsyncDataKeyProvider`, `KmsAsyncDataKeyProvider`, `AsyncEnvelopeEncryptor` y la
fachada diferida `ReactiveEnvelopeEncryption`. El formato se comparte con los sobres anteriores.
Las cachés acotan entradas y cargas pendientes, reúnen peticiones concurrentes, reservan el límite
de usos por clave y cancelan la carga nativa cuando deja de haber consumidores. Esta lógica
común se extrajo a `AsyncLoadingCache` para reutilizarla en secretos sin duplicar algoritmos.
Aprobaron 21 pruebas de core, 6 del adaptador y 13 de Spring, con cobertura y análisis estático.
La prueba real `kms-shared-cache-integration2.log` aprobó 3 integraciones bajo BlockHound tras
la extracción: interoperabilidad, 40 operaciones con rotación exacta por usos, vencimiento,
refresco de credenciales y reconexión; también rechazo del contexto y sobres inválidos antes
de llamar a KMS. La mutación de commons y crypto aprobó en
`native-loading-cache-pit1.log`; el adaptador aprobó 14 de 14 mutantes en
`crypto-native-pit1.log`. Spring no tiene PIT configurado. El gate completo de API aprobó
antes de empaquetar la extracción; se volverá a ejecutar contra todos los JAR actualizados.

Se implementó además `DynamoAsyncRepository`, con fachada `ReactiveDynamoRepository` y factoría
`ReactiveDynamoRepositories`. La autoconfiguración distingue servlet/reactive y reutiliza clientes
propios. El kit conserva bloqueo optimista, escrituras condicionales, transacciones, cursores e
índices; no precarga la página siguiente. La cancelación alcanza el futuro nativo, algo que el
futuro del Enhanced Client por sí solo no garantiza. Aprobaron 16 pruebas del kit y 7 de Spring,
incluidos quality y cobertura. `dynamo-native-integration1.log` aprobó 3 integraciones reales con
LocalStack y BlockHound: CRUD en frío, paginación/índices y ocho actualizaciones concurrentes con
la misma versión (solo una confirma). No se detectaron bloqueos ni errores de cierre. El
generador ya selecciona una plantilla de repositorio reactiva para DynamoDB. Aprobaron sus
100 pruebas, la mutación del generador y del kit, y las 48 comprobaciones de arquitectura
(`dynamo-native-pit-codegen-architecture1.log`).

Inbox ya ofrece el puerto JDK `AsyncInboxStore`, el contrato nativo `ReactiveInboxStore` para
R2DBC y la fachada Spring `ReactiveInbox`. Sus variantes comparten SQL y condiciones de
Se corrigió además la inicialización de recursos con `axiz.inbox.enabled=false`. Aprobaron
quality y mutación de core y los dos adaptadores (`inbox-native-pit1.log`), las 12 pruebas de
Spring y sus controles de calidad (`inbox-native-spring-quality6.log`). La suite
`inbox-native-integration1.log` aprobó 38 integraciones sin omisiones: 19 de PostgreSQL
(incluido rollback de inbox tras cambiar de hilo), 16 de DynamoDB y 3 del repositorio nativo.
Los contratos comunes ejecutan las llamadas nativas en hilos comprobados por BlockHound.
El gate de API completo volvió a aprobar las 185 rupturas previstas y 161 reubicaciones.
La inspección posterior de 63 reportes PIT contó 4.751 mutantes detectados, 74 timeouts,
831 supervivientes y 324 sin cobertura, sin errores de procesos de mutación. Es un agregado
de ejecuciones por módulo; los módulos Spring no tienen una ejecución PIT configurada.

La segunda compilación aislada (`fresh-native-compile1.log`) aprobó los 159 módulos desde
fuentes sin `target` y con repositorio Maven independiente sin artefactos privados iniciales.
Tras esa validación se añadió `PresignedUpload`: URI y headers firmados completos e inmutables,
incluidos los de SSE-KMS. Se conservaron los métodos anteriores. Las pruebas específicas de core
(14), adaptador S3 (12), Spring (12), quality y PIT de core/adaptador aprobaron. La carga HTTP
real mediante estas instrucciones y TLS aprobaron 4 integraciones adicionales
(`signed-upload-integration3.log`). El gate de compatibilidad mantiene las 185 rupturas
previstas y 161 reubicaciones; `javap` confirmó que la firma nueva es aditiva.

Las secuencias ya tienen un generador asíncrono en Java puro, almacenamiento DynamoDB nativo,
puerto/implementación R2DBC y fachada diferida `ReactiveSequences`. Las reservas usan una transacción
propia y pools acotados independientes del negocio: un rollback o un pool principal saturado no
pueden reutilizar rangos reservados ni impedir la reserva. El pool JDBC consulta las credenciales
vigentes al crear conexiones. Se corrigieron también el cambio de período al cruzar medianoche y
el desbordamiento del último bloque.

`sequence-native-integration4.log` aprobó 15 integraciones con PostgreSQL y DynamoDB reales:
concurrencia entre réplicas, rollback, cancelación, saturación, renovación de credenciales y
reconexión. Las suites funcionales y de calidad de secuencias y sus dependencias pasaron; PIT de
core-commons, core-sequence, kit-r2dbc y observability-aws terminó sin errores de procesos.
La ejecución Maven también validó los módulos Spring seleccionados; su parent no ejecuta PIT.

El ejecutor de terminaciones AWS tiene dos trabajadores preiniciados y admisión acotada, sin
`caller-runs`. BlockHound mantiene vigiladas sus tareas. Solo permite la espera ociosa en
`ThreadPoolExecutor.getTask` y el cierre de pools Netty en `SimpleChannelPool.close`; no se amplió
el permiso al código de aplicación. Una sonda comprueba errores de cierre del SDK.

El almacenamiento nativo incorpora `AsyncObjectStorage`, `AsyncStoredObject`, una implementación
local acotada, `S3AsyncObjectStorage` y `ReactiveStorage`. Sus pruebas verifican demanda, propiedad
de los buffers, respuestas tardías, cierre sin consumir y cancelación de la página HTTP activa.
La suite conjunta `native-acceptance1.log` aprobó 19 integraciones con PostgreSQL, DynamoDB, S3 y
TLS reales, sin fallos ni omisiones. S3 incluye 32 cargas concurrentes con checksum automático,
transmisión de 512 KiB con verificación SHA-256, demanda controlada, cierre sin consumir,
cancelación, paginación, objetos ausentes y reconexión. La prueba TLS retira el archivo de
confianza después de construir el cliente y abre conexiones nuevas; también verifica que un
hostname incorrecto y un certificado no confiable causen un fallo de handshake.

Se corrigieron tres fuentes adicionales de bloqueo descubiertas por esas pruebas: la expansión
con bloqueo del asignador adaptativo de buffers de Netty, la carga diferida de la configuración
XML del JDK y la lectura diferida del almacén de confianza TLS. El transporte AWS usa buffers
sin ese pool adaptativo y prepara proveedores XML y trust managers al arrancar, manteniendo
validación estricta de certificados y hostname. El adaptador declara su dependencia de DNS
asíncrono: una llamada HTTP real con su classpath mínimo comprueba que funciona sin otro starter.

`BlockHoundExtension` conserva como máximo 32 sitios originales de bloqueo y sigue lanzando el
error. Las integraciones exigen que ese registro esté vacío, de modo que una excepción posterior
del SDK no pueda ocultar el bloqueo. No se añadió una excepción para asignadores, XML, TLS ni
lectura de archivos. Las reglas de arquitectura también detectan ahora las interfaces situadas
en el paquete raíz `org.reactivestreams`; su prueba negativa y las otras 47 comprobaciones pasan.

Estos resultados cubren el transporte y las operaciones implementadas. La apertura de las
conexiones probadas sucede de forma nativa después de construir el cliente; todavía debe
completarse la política explícita de precalentamiento de pools físicos al arrancar, junto con las

## Correcciones surgidas de las pruebas

- Se conserva la identidad de eventos, consumidor, grupo Kafka y Parameter Store al renombrar.
  `.axiz/identities.yaml` registra los valores; doctor detecta su alteración. Se generan aliases DNS
  en Helm y se amplía la audiencia cuando depende del nombre anterior. Los recursos persistentes,
  client IDs OAuth, rutas de secretos y paquete Java permanecen explícitos.
- La caché asíncrona descuenta de su TTL el tiempo de lectura, propaga cancelación al futuro nativo y
  conserva la política degradable incluso si la llamada remota falla antes de devolver el futuro.
- El SDK cerraba varias veces el proveedor compartido de credenciales al cerrar clientes. Los
  clientes reciben ahora una vista sin propiedad de cierre; el framework conserva la propiedad del
  refrescador. El cierre es idempotente y las lecturas posteriores fallan explícitamente.
- SCRAM PostgreSQL reveló lecturas de entropía en el hilo de red con el generador predeterminado de
  Linux. Se configura el DRBG estándar del JDK y se inicializa al arrancar, sin relajar BlockHound.
  Los servicios reactivos validan esta condición. El parent de servicios aplica la opción tanto a
  pruebas como a Jib y `spring-boot:run`; Helm generado la incluye.
- El logging reactivo predeterminado usa una cola acotada y un appender asíncrono a stdout. Se retiró
  la excepción amplia de BlockHound sobre todos los appenders síncronos. La configuración explícita
  del servicio prevalece y debe conservar estas garantías.
- El build de librerías aplica la versión final a las coordenadas y referencias del reactor,
  conservando la herencia y las expresiones CI-friendly. Evita `-Drevision` global, que podía
  modificar propiedades del parent Axiz externo. Los 159 modelos del framework se validaron con
  una versión alternativa en otra copia; los 159 módulos también compilaron con quality y un
  repositorio Maven aislado. Esta prueba no publicó coordenadas ni sustituyó las del proyecto.
- Los bundles se identifican por el hash del manifiesto, de modo que varios productos de la misma
  ejecución no colisionan. Los charts tienen un namespace por repositorio/run/attempt/variante.
  El build exige ocho evidencias en servicios y recorre todas las plataformas al escanear la imagen.
- El deploy de servicios compara el manifiesto anterior antes de escribir en GitOps. Firma la
  solicitud y el resultado, conserva ambos y sus hashes, y verifica que Argo haya sincronizado
  los digests/revisiones aprobados; una aplicación anterior sana no permite declarar éxito.

## Continuación: credenciales HTTP nativas

Se incorporaron `AsyncAccessTokenProvider` y `AsyncHttpExchange` en core HTTP, con contratos Java
independientes de Reactor. El proveedor de client credentials comparte la política de wire y
validación con el imperativo, reúne renovaciones concurrentes y cancela el secreto o transporte
activo. Valida URL, timeout, tipo/expiración del token y tamaño real de respuesta. Los errores JSON
no exponen credenciales; `AccessToken` y el cuerpo de respuesta se enmascaran en todos los canales.

En Spring, WebClient/Reactor Netty implementa el transporte con DNS nativo, pool acotado, TLS
estricto y recursos propios inicializados al arrancar y cerrados con la aplicación. El filtro ya
no obtiene credenciales mediante `boundedElastic`; consume el puerto asíncrono y conserva el
contexto de observación con scopes locales. También se corrigió la liberación de permisos de
bulkhead/circuit breaker al cancelar, recibir una respuesta vacía o fallar al enviar.

`oauth-native-core-final-quality4.log` aprobó 15 pruebas, quality y PIT (105 mutantes, 84 eliminados,
80 %, cobertura de mutación 94 %). `oauth-native-spring-quality8.log` aprobó 22 pruebas y quality,
con SpotBugs sin hallazgos. Incluyen HTTP real, concurrencia y rotación, cancelación del secreto,
rechazo de redirecciones/respuestas grandes, contexto padre, aislamiento sin dependencias reactivas,
y dos conexiones HTTPS nuevas después de retirar el truststore; hostname y CA incorrectos se
rechazan. BlockHound comprueba los sitios originales de bloqueo sin excepciones de producción nuevas.

El ejecutor acotado de callbacks se consolidó en core commons; el SDK AWS reutiliza ese mismo
algoritmo (admisión inmediata, presupuesto limitado, sin caller-runs). Commons aprobó 56 pruebas y
PIT (`oauth-native-port-core-quality2.log`); el SDK aprobó 19 pruebas y PIT, 90 % de mutación
(`aws-shared-callbacks-quality3.log`). La comparación de API actualizada de los 159 módulos y su
control estricto aprobaron (`native-context-oauth-api1.log`,
`native-context-oauth-api-observed2.json`): se revisó únicamente la huella de reemplazo del método
aditivo de trazas; permanecen las 185 rupturas previstas y las 161 reubicaciones.

## Continuación: auditoría y CQRS nativos

Core ofrece `AsyncAuditEventPublisher`, `AsyncAuditTrail` y `AuditRecord`: una sola política de
construcción, sanitización y snapshots para los caminos imperativo y nativo. SQS comparte también
el formato CloudEvents y los identificadores FIFO entre ambos adaptadores; el puerto confirma la
aceptación real y cancela el futuro exacto del SDK. El fan-out nativo intenta todos los sinks e
informa el fallo, sin ocultarlo. El antiguo `AsyncAuditPublisher` sigue siendo la cola imperativa
para servlet/workers; no se presenta como un transporte nativo.

Spring selecciona sinks nativos, rechaza sinks imperativos incompatibles y respeta el interruptor
global. La anotación y el filtro HTTP crean un registro por suscriptor, esperan la entrega al
completar, conservan el error del negocio y propagan cancelación al envío activo. La prueba inicial
expuso que una limpieza de recurso podía proteger el envío contra cancelación; se corrigió la
composición y se prueba el futuro del transporte, incluso al cancelar durante la entrega.

La auditoría durable conserva el Context de Reactor y escribe mediante R2DBC en la misma
transacción. El advisor nativo se ordena dentro del caso de uso hexagonal; una integración con
los advisors reales prueba ese orden. Las otras integraciones prueban commit conjunto, rollback
del negocio y rollback si no se puede escribir evidencia. Los fallos salen por el fallback
nativo, porque no deben desaparecer con el rollback del negocio. El log reconoce hand-off al
appender no bloqueante; la durabilidad financiera corresponde al outbox.

CQRS ya no contiene offload: utiliza políticas reactivas, `AsyncIdempotencyService` y
`ReactiveAuditTrail`. Rechaza al arrancar comandos que necesitan puertos nativos ausentes;
conserva los descriptores de los constructores anteriores, que ahora rechazan los servicios
imperativos explícitamente. La cancelación cancela la operación activa y libera el lease con
fencing; un resultado completado sigue disponible para replay. Se añadió una regla global del
framework contra schedulers elásticos, y las reglas de servicios rechazan también auditoría y
OAuth imperativos, permitiendo sus DTO y puertos nativos.

Evidencia específica: core audit 10 pruebas/PIT 82 % (84 mutantes, 69 eliminados); adaptador SQS
6 pruebas/PIT 100 % (9/9), quality y SpotBugs (`audit-native-core-final-quality3.log`). Spring audit
18 pruebas y SpotBugs sin hallazgos; arquitectura 50 pruebas; integraciones reales 8, sin omisiones,
con SQS, reconexión, refresco de credenciales, contexto padre y PostgreSQL
(`audit-native-architecture-integration4.log`). CQRS Spring aprobó 19 pruebas, incluidas cuatro
regresiones nativas con BlockHound (`cqrs-native-final-quality5.log`). El aviso de nombre del puente público legado `ReactiveAccessPolicy` se revisó como falso positivo
de compatibilidad y se acotó a esa clase exacta; las 19 pruebas y SpotBugs sin hallazgos se
repitieron en `native-messaging-notification-architecture-quality5.log`.

Las reglas de servicio y el API CQRS aprobaron sus controles y PIT después de ampliar las
prohibiciones y comprobar los DTO permitidos (`audit-cqrs-reactive-rules-quality1.log`). Estas
pruebas específicas no sustituyen la aceptación global de todas las capacidades pendientes.

## Continuación: routing y notificaciones SQS/SNS

Core incorpora `RoutingAsyncEventPublisher`, `AsyncNotificationSender` y
`AsyncEventNotificationSender`. La selección se hace antes del envío; no existe un fallback a otro
broker cuando falla el seleccionado. Notificaciones comparte un solo constructor de eventos con
la variante imperativa, conservando id, correlación, esquema y deduplicación.

Los adaptadores SQS/SNS comparten la construcción de peticiones entre stacks. Los errores de
submission se representan en el stage; la cancelación llega al futuro exacto del SDK y no se
transforma en un error de publicación ordinario. SQS también reconoce URLs HTTP de emuladores,
y rechaza direcciones mal formadas o con credenciales/query/fragmento.

Spring ofrece `ReactiveEvents`, `ReactiveNotifications`, routing observado y adapters nativos.
Valida destinos al arrancar, respeta los interruptores globales y crea recursos AWS propios solo
para destinos configurados; los clientes aportados por la aplicación se reutilizan. Los puertos
imperativos se conservan en los bordes de poll/relay, sin usarlos como fallback reactivo.
Las observaciones de publicación conservan su padre, inyectan W3C en CloudEvents y continúan la
traza almacenada del outbox; no mantienen un scope abierto entre hilos ni instalan hooks globales.

Core messaging aprobó 16 pruebas/PIT 80 % (145/116); notification 4 pruebas/PIT 100 % (13/13);
SQS 5 pruebas/PIT 96 % (28/27); SNS 4 pruebas/PIT 100 % (15/15), además de quality y SpotBugs
(`native-messaging-notification-core-final-quality3.log`). El filtro de SpotBugs incorpora dos
falsos positivos concretos y documentados: el registry prestado de la observación nativa y el
nombre del puente legado de CQRS; no se modificaron umbrales ni reglas generales.
Spring messaging 46 pruebas (47 tras añadir la regresión de redacción), notification 3, CQRS 19
y arquitectura 50 aprobaron desde cero,
con SpotBugs sin hallazgos (`native-messaging-notification-architecture-quality5.log`).

`native-messaging-notification-integration3.log` aprobó una integración real con SQS/SNS:
publicación directa, notificación vía SNS→SQS, reconexión y refresco de credenciales, spans padre,
error de cola inexistente y deduplicación FIFO entre productores imperativos y nativos con el
mismo id. BlockHound y el probe de ciclo de vida quedaron sin bloqueos originales ni errores.
La confirmación de la notificación acredita aceptación del broker, no entrega al destinatario.

La comprobación final añadió una regresión de datos sensibles: los destinos rechazados no se
incorporan a trazas y los errores de arranque muestran solo el alias lógico. Core y sus mutaciones
aprobaron esa corrección (`native-messaging-redaction-quality6.log`, etapa core); Spring aprobó
sus 47 pruebas y quality con SpotBugs sin hallazgos (`native-messaging-redaction-quality7.log`).
La suite completa de backends se repitió sin filtro: **67 integraciones reales**, sin fallos,
errores ni omisiones, con Docker obligatorio (`native-messaging-full-store-acceptance1.log`).
La API de SQS añadió solamente `isDestination(String)`, revisado mediante `javap` sin pérdidas
(`native-messaging-api-public-review.json`); el control exacto mantuvo 185 rupturas previstas y
161 reubicaciones. Las regeneraciones posteriores verifican la frescura de los JAR y reportes.

## Continuación: plantillas de pipelines independientes

Los proyectos de servicio, librería y starter generan `ci.yaml` de build y `deploy.yaml`
independiente; el generador ya no emite `cd.yaml`. El build toma versión, SHA completo, variante,
plataformas/base por digest y la identidad opcional de una candidata del framework. Los
consumidores apuntan a los contratos `lib-build/lib-deploy` o `service-build/service-deploy`
2.0.0. Se separaron credenciales de lectura, escritura del almacén de builds y promoción.

El deploy exige run, attempt y hash externo del manifiesto, ambiente y manifiesto anterior;
para servicios también el commit completo de values. No ejecuta Maven ni recibe una etiqueta
suelta de imagen. Librerías y starters generan releases en borrador para que la promoción
verificada publique el release. El README generado explica la configuración de la plataforma.

Las 100 pruebas funcionales/de calidad del motor aprobaron, incluidas comprobaciones
estructurales del build/deploy generado (`codegen-independent-pipelines-quality3.log`). Se
renderizaron tres proyectos reales (servicio, librería y starter): actionlint aprobó sus nueve
workflows y el parser JSON verificó sus configuraciones de release
(`generated-pipelines-actionlint2.log`, fixtures `generated-workflows-v2/`). No se presenta esto
como una ejecución remota ni como nueva mutación del motor: sus clases de producción no cambiaron;
se modificaron recursos de plantillas y sus pruebas.

## Continuación: consumidores, binarios y pipelines

Tools y los ocho servicios actuales de la demo, tanto en el agregador como en sus carpetas
independientes, usan los workflows 2.0 de build y deploy. Se retiraron los cuatro exports
`lib-publish/lib-promote/service-ci/service-cd` y la acción GitOps anterior sin consumidores.
Tools conserva dos productos con identidades distintas: el reactor Maven y el servicio Initializr.
Sus promociones son independientes. Los builds reciben las mismas candidatas del framework y
usan credenciales de lectura y de escritura del almacén de builds separadas de las de deploy.

Los componentes nativos del CLI ya no adjuntan archivos a releases durante el build. Cada OS
produce un archivo determinista, metadata canónica y evidencia de smoke, con firma propia.
`lib-build` verifica las firmas y la coincidencia de repositorio, SHA, versión, parámetros,
dependencia Axiz, run e intento; después incorpora los tres componentes como classifiers Maven
al único manifiesto final. El gate `native` liga archivos y evidencias. La promoción conserva los
aliases de descarga del CLI y sus checksums antes de publicar el draft. Rechaza sobrescrituras,
componentes de otros intentos, plataformas ausentes y manifiestos ajenos al release. El consumidor
público sigue exigiendo que **toda** la ejecución productora haya terminado con éxito.

Aprobaron 61 pruebas del contrato de artefactos, incluidas las nuevas regresiones de componentes
nativos y promoción HTTP local (`native-components-all-contracts3.log`), 21 pruebas de políticas
(`native-pipeline-security-tests3.log`), el control de 31 consumidores reales/generados y sus
contratos, y actionlint de los workflows. La comprobación de seguridad recorrió 56 archivos
activos (`native-pipelines-security-final2.log`). Los baselines 1.0.0 del migrador son datos
`.fixture`, no workflows emitidos. No se presenta la verificación local con firmas simuladas de
las pruebas unitarias como una ejecución OIDC o publicación real en GitHub.

## Continuación: capas de starters y migración de proyectos completos

Un starter Axiz ahora genera `axiz-core-<capacidad>` bajo `pe.axiz.core.<capacidad>` y la
configuración Spring bajo `pe.axiz.spring.<capacidad>`. Conserva los nombres
`axiz-<capacidad>-spring-boot-*`; el agregador hereda el parent común y sus módulos mantienen los
parents de su capa. Se corrigió también la descripción del catálogo, que aún llamaba capa 2 al
starter Spring. Se aceptan nombres de entrada con o sin el prefijo `axiz-core-` sin duplicarlo.

El motor aprobó 101 pruebas, cobertura, SpotBugs y PIT. La última ejecución de mutación registró
1.287 mutantes, 920 eliminados (71 %) y cobertura de líneas del 92 %
(`codegen-rename-chart-quality1.log`). Un starter real con sus cuatro POMs aprobó formato,
compilación, pruebas, cobertura, controles de calidad y mutación
(`generated-starter-layers-real-quality3.log`).

La prueba de actualización real encontró que cambiar el parent antes de OpenRewrite dejaba las
coordenadas 1.x sin versión gestionada e impedía arrancar la receta. `upgrade` ahora ejecuta el
proceso en una copia privada, conserva el modelo Maven anterior para su resolución y prepara
schema 2 para la receta. Al terminar actualiza todos los parents Axiz del reactor, mantiene las
versiones de la aplicación y valida antes de aplicar. Un fallo de ejecución o validación deja el
original intacto; la aplicación detecta ediciones concurrentes y restaura los archivos previos si
falla una escritura posterior. Git, caches, targets y POMs generados no se aplican al original.
El parser Maven rechaza entidades externas, ciclos, enlaces y módulos fuera del proyecto.
`doctor` comprueba también los módulos de agregadores sin parent ni fuentes propios.

Los workflows estándar 1.0.0 se reconocen comparando su YAML con el baseline retenido y pasan a
build/deploy independientes. Las personalizaciones producen una propuesta y detienen la
aplicación, conservando el original. Un renombre mantiene namespace Java, identidades de
consumo, fuente de eventos, Parameter Store y aliases; actualiza el nombre del chart conservando
su ruta. El plugin OpenRewrite se fija desde el parent en los recursos filtrados del CLI. Se usó
6.46.1, disponible en Maven Central: las versiones posteriores cambiaron de repositorio, como
consta en el [POM del proveedor](https://github.com/openrewrite/rewrite-maven-plugin/blob/v6.49.0/pom.xml).
No se añadió un repositorio directo que evite el mirror Nexus.

Los tres proyectos completos 1.x —layered, hexagonal y CQRS— ejecutaron recetas reales,
renombre y migración a 2.0; después aprobaron 11 pruebas, incluyendo arquitectura, con cero
fallos, errores u omisiones (`migration-project-acceptance/migrated-*-verify1.log`). Sus pipelines
migrados pasaron los contratos de build/deploy, actionlint, la identidad del chart y el ledger
(`migration-project-acceptance/pipelines-contracts-final1.log`). Un dry-run real conservó los 76
archivos originales (`migration-project-acceptance/dry-run-integrity1.log`).

La validación funcional de esos esqueletos de negocio no sustituye la calidad de una aplicación
implementada: el baseline 1.x generaba stubs y su cobertura inicial era 31–34 %, por debajo del
umbral. No se rebajó el gate. El CLI aprobó 60 pruebas, quality y PIT: 592 mutantes, 431 eliminados (73 %) y cobertura
de líneas del 92 % (`cli-workflow-migration-final-quality1.log`). El CLI nativo actualizado compiló con GraalVM 25 y CPU compatible, aprobó esas 60 pruebas
y ejecutó `doctor` real con cero errores y advertencias (`cli-native-independent-pipeline-acceptance4.log`,
`native-cli-doctor-final1.log`). Generó y formateó un starter desde el ejecutable, y su dry-run
real conservó los 76 archivos originales y produjo un parche aceptado por `git apply --check`,
incluidos los pipelines independientes y excluidos los POMs generados
(`native-cli-generated-starter-final1.log`, `migration-project-acceptance/native-dry-run-final1.log`).
El starter generado por el CLI nativo aprobó directamente `verify -Pquality`, sin ajustes manuales,
con sus cuatro POMs (`native-cli-generated-starter-quality1.log`). La API se regeneró para los 159
módulos y el control estricto mantuvo las 185 rupturas previstas y 161 reubicaciones aprobadas
(`native-pipelines-api-final-refresh4.log`, `native-pipelines-api-final-observed3.json`). Los 63
reportes de mutación del framework no presentan errores de proceso
(`native-pipelines-mutation-health1.log`); la documentación sincronizada y MkDocs estricto
aprobaron (`native-pipelines-tools-docs-final1.log`). SpotBugs mantiene un aviso de ejecución de procesos en `ProcessRunner.Local`:
se revisó el uso de argumentos separados, sin shell; no se añadió una exclusión global.

## Evolución AsyncAPI y evaluación estricta de Kafka (continuación del 1 de noviembre)

El motor ahora interpreta operaciones `send` y `receive` de AsyncAPI 3, sus referencias locales
y los nombres de tipos CloudEvents terminados en `.vN`. Rechaza YAML con claves duplicadas,
referencias externas o cíclicas y expansiones excesivas. La generación inicial deriva los records
Java y las suscripciones del contrato; los records de eventos usan tipos JDK y no anotaciones
externas. En hexagonal se generan en `domain.event`. Se comparte el mapeo de tipos con los modelos
de aplicación y se elimina el ejemplo EDA cuando se entrega un contrato real.

`axiz contract sync --type asyncapi` crea records o propuestas `.axiz-new` y conserva el código
existente. No permite reemplazar un tipo publicado mediante `--allow-breaking`. `lint` permite
validación estructural local y `diff --type asyncapi` comprueba compatibilidad; las reglas Spectral
corporativas siguen ejecutándose en CI. `axiz generate event` lee el payload declarado, y `doctor`
compara las suscripciones de YAML/properties y sus perfiles con los tipos consumidos. Los handlers
siguen siendo responsabilidad del servicio; la comprobación de arranque rechaza los que faltan.

El pipeline exige el snapshot AsyncAPI y lo compara semánticamente con el código fuente. Además,
compara los tipos publicados contra el contrato de un commit anterior inmutable: base de PR,
commit anterior del push o release anterior alcanzable en builds manuales/tags. Editar juntos
fuente y snapshot ya no oculta una ruptura. Se prueban adiciones opcionales anidadas, retiro,
campos obligatorios, cambios de tipo, convivencia de versiones, referencias y diferencias entre
booleanos y números. Cambiar el significado de un campo requiere revisión funcional: no puede
inferirse de un esquema sin cambios. La retirada definitiva de vN queda cerrada por defecto hasta
contar con evidencia de migración de todos sus consumidores; no se añadió una omisión del gate.

Validación funcional inicial en Fedora Remix 42 WSL2 / Java 25: 113 pruebas del motor y 64 del CLI,
sin fallos ni omisiones. Se corrigieron avisos estáticos de nulabilidad y patrones de nombres,
incluidos los del renombrado de proyectos. Los gates finales de mutación y la aceptación nativa de
esta ampliación siguen ejecutándose; se registrarán al terminar.

de alternativas descartó [Vert.x](https://raw.githubusercontent.com/vert-x3/vertx-kafka-client/master/src/main/java/io/vertx/kafka/client/producer/impl/KafkaWriteStreamImpl.java)
porque ejecuta `Producer.send` en un worker, y [SmallRye](https://smallrye.io/smallrye-reactive-messaging/smallrye-reactive-messaging/3.8/kafka/kafka.html)
porque documenta un hilo de envío para evitar bloquear al llamador. Se inspeccionó
[kafka-client-netty en un commit fijo](https://github.com/protocol-laboratory/kafka-client-netty/tree/8da8b7ecc85c61b6954d37fccd677a08494ac663):
usa transporte Netty pero su cliente no demuestra límites/timeout y limpieza de solicitudes ante
desconexión ni las garantías de un productor financiero completo. [finagle-kafka](https://github.com/okapies/finagle-kafka/tree/b7ad1422e303b5ff6e8454d56c1bb093fb549a5e)
aún declara como trabajo futuro la actualización a Netty 4/5, compresión y pruebas de compatibilidad.
No se adoptó ninguna de estas dependencias ni se relajaron las guardas. Esto no prueba que no
exista otro cliente apto; Kafka sigue pendiente de implementación y aceptación real.

## Aceptación de AsyncAPI y despliegue automático independiente

Se incorporó `workflow_run` en la plantilla de servicios, en Initializr y en los ocho consumidores
actuales de la demo, conservando el modo manual. La resolución automática sólo lee Actions y
GitOps. Usa un comprobante auxiliar firmado por el productor que enlaza hash, commit, ejecución,
intento, servicio y módulo; no agrega otro tipo público de manifiesto. El consumidor autentica
la firma antes de interpretar el comprobante, relee la identidad de Actions y exige el HEAD de
main protegido. Los ZIPs tienen nombres, tamaño y entradas limitados antes de extraerlos. El
predecesor se lee en un commit GitOps inmutable y se comprueba otra vez mediante CAS al escribir.
El deploy rechaza un destino de ambiente diferente del observado por el resolver. `dev` se
promueve automáticamente; QA, producción y rollback conservan selección manual explícita.

Pasaron las 78 pruebas de manifiestos/pipelines, incluidas las regresiones del presupuesto global
de expansión de AsyncAPI, y las 24 de políticas. La validación final cubre 35 consumidores: los
31 anteriores y cuatro workflows de los dos proyectos reactivos migrados en esta continuación.
También aprobaron los dos pares de contratos reutilizables y cuatro configuraciones de release.
Actionlint aprobó 48 workflows del proyecto, 12 de plantillas renderizadas y seis de proyectos
migrados; el control de seguridad aprobó sus 56 archivos, incluidas las plantillas. La configuración está en
`axiz-workflows/docs/pipelines-v2.md`: se necesita `GITOPS_READ_TOKEN` de sólo lectura. Las pruebas
locales simulan cosign y las respuestas API; no constituyen aceptación de OIDC ni despliegue remoto.

El ciclo final de calidad del motor aprobó 120 pruebas, cobertura y cero hallazgos SpotBugs
(`asyncapi-event-adapters-quality2.log`). PIT generó 1.498 mutantes: 1.104 eliminados, uno detectado
por timeout de mutación, 318 supervivientes y 75 sin cobertura; resultado 74 %, por encima del
umbral configurado. El CLI aprobó 68 pruebas y quality (`asyncapi-event-handlers-cli-quality1.log`):
703 mutantes, 509 eliminados, dos detectados por timeout, 157 supervivientes y 35 sin cobertura;
resultado 73 %. No hubo errores de procesos de mutación. El único aviso SpotBugs del CLI es el
uso previamente revisado de `ProcessBuilder(List)` sin shell; no se ocultó con una supresión global.

La ejecución real del servicio generado encontró dos defectos adicionales. `doctor` exigía una
propiedad de autodetección innecesaria aunque el test ya declaraba `@ExtendWith(BlockHoundExtension.class)`.
El diagnóstico ahora reconoce la anotación explícita y excluye comentarios/literales Java. Su
ciclo aislado aprobó 67 pruebas, quality y 686 mutantes, con 497 detectados. Después, el arranque
Spring detectó la ausencia de handlers para las suscripciones generadas del contrato.

`EventAdapters` ahora genera esqueletos tipados por cada tipo recibido y metadatos AOT por payload
en `config`, fuera del dominio. Reactive usa `ReactiveTypedEventHandler` con `Publisher<Void>`;
servlet usa `TypedEventHandler`. Los esqueletos rechazan los mensajes hasta implementar el negocio,
en vez de confirmar trabajo inexistente. La sincronización conserva implementaciones y metadatos
existentes; las propuestas de cambios se reservan a los payloads. El CLI genera `event`, `handler`
y `event-handler` desde el contrato y verifica la capacidad de mensajería. Las bibliotecas y
starters no aceptan contratos de servicio. El presupuesto de referencias se comparte entre todas
las operaciones del documento, evitando multiplicar el trabajo mediante referencias repetidas.

Con el motor actualizado, un servicio hexagonal/reactivo real con PostgreSQL y LocalStack aprobó
sus cuatro pruebas, incluido arranque con BlockHound y arquitectura (`asyncapi-handler-service-verify1.log`).
Una prueba adicional sobre sus clases compiladas aprobó tipo/payload reales, JSON con campos
opcionales desconocidos, roundtrip y rechazo del handler todavía sin implementar
(`asyncapi-generated-codec-handler-acceptance1.log`). Los payloads no contienen anotaciones Spring;
los metadatos de binding están en configuración.

El CLI nativo final compiló con GraalVM 25 y aprobó sus 68 pruebas
(`asyncapi-final-native-cli1.log`). Con ese ejecutable se creó otro servicio hexagonal/reactivo
con PostgreSQL y SQS; `doctor` aprobó sin propiedad de autodetección JUnit. Se comprobaron
generación de eventos y handlers, lint, propuestas de campos opcionales y conservación exacta
de handlers/configuraciones existentes. Diff, sync y `--allow-breaking` rechazaron un cambio
incompatible del tipo publicado sin alterar el snapshot ni la implementación. Tras aceptar
explícitamente la propuesta compatible en este proyecto temporal, doctor volvió a aprobar
(`asyncapi-final-native-flow1.log`). El servicio pasó sus cinco pruebas, incluido arranque con
PostgreSQL/LocalStack, BlockHound, arquitectura y codec/handler/AOT
(`asyncapi-final-native-service-verify1.log`). Esta aceptación utiliza el CLI nativo para generar;
esa primera ejecución de la aplicación fue en JVM. La aceptación ELF posterior se registra abajo.

La comparación API final de los 159 módulos coincide con las 185 rupturas y 161 traslados
revisados (`native-reactive-final-api-refresh1.log`, `native-reactive-final-api-check1.log`). La documentación
sincronizada pasó MkDocs estricto (`asyncapi-final-docs-build1.log`).

El procesamiento AOT del servicio también aprobó (`asyncapi-final-service-aot1.log`); se comprobó
que el archivo real `reachability-metadata.json` registra constructores, campos y accesores del
payload, incluido el campo opcional nuevo. La compilación ELF posterior encontró un defecto
adicional: `r2dbc-proxy` solicita inicializar su paquete al construir la imagen, pero su listener
de observación crea un logger Micrometer que pertenece a la ejecución. El starter R2DBC incorpora
ahora metadatos nativos que difieren únicamente `ObservationProxyExecutionListener` hasta runtime.
Se mantienen logs, métricas y tracing; no se fuerza a inicializar Micrometer ni su backend al
construir la imagen. El starter corregido aprobó quality y empaquetado
(`r2dbc-native-logging-metadata-quality1.log`).

El primer arranque ELF encontró otra diferencia respecto de JVM: GraalVM captura el PRNG
predeterminado durante el build. Pasar `java.security.egd` al ejecutar el binario era demasiado
tarde y el control estricto rechazaba un algoritmo distinto de DRBG. Una imagen mínima comprobó
la causa (`native-drbg-probe-runtime1.log`). El perfil `native` de `axiz-parent-service` ahora
selecciona DRBG al construir la imagen; el control de arranque permanece estricto.

El servicio se reconstruyó con ambos cambios (`asyncapi-final-service-native-compile3.log`). Su
ELF arrancó sin parámetros PRNG adicionales y aprobó health, liveness y readiness con PostgreSQL
y LocalStack reales (`asyncapi-final-service-native-runtime-acceptance3.log`). El verificador
conserva el hash y los resultados en `target/native-runtime-result.json` del proyecto generado
y limpia sólo los contenedores que crea. Esta aceptación comprueba infraestructura y arranque;
los handlers de negocio del esqueleto siguen rechazando trabajo todavía sin implementar.

## Precisión decimal de eventos y regresión permanente JVM/native

La aceptación detectó pérdida de precisión en propiedades JSON anidadas sin tipo numérico Java
explícito: `0.123456789012345678901234567890` se convertía a `Double` y quedaba como
`0.12345678901234568`. La regresión reprodujo el defecto antes de corregirlo
(`event-nested-decimal-regression-red1.log`). `JacksonPayloadCodec` configura su copia del mapper
con `USE_BIG_DECIMAL_FOR_FLOATS`, conservando decimales de objetos y listas genéricos. La prueba
comprueba el valor exacto, el roundtrip y que el mapper HTTP conserva su configuración.
Las 48 pruebas de messaging aprobaron quality, cobertura y cero hallazgos SpotBugs
(`event-nested-decimal-quality1.log`). También se repitió la integración real SQS/SNS con
BlockHound, aprobada (`event-nested-decimal-messaging-it2.log`).

Se añadió el workflow local `native-reactive.yaml`, usado por CI y el nightly. El control de
publicación exige este gate y rechaza su ausencia, ejecución condicional, tolerancia a errores
o reemplazo por un paso vacío. `.github/scripts/reactive-native-sample.sh` instala el framework
desde fuente, genera por la API pública un servicio hexagonal/reactivo con Aurora, SQS y AsyncAPI,
y ejecuta sus pruebas JVM con BlockHound. Una prueba generada adicional comprueba precisión decimal,
evolución aditiva, wire type, binding AOT, dominio Java puro y rechazo del handler sin implementar.
Después compila el ELF y comprueba sus tres sondas con dependencias efímeras, sin flags PRNG de
runtime. La comprobación ELF no sustituye BlockHound en JVM ni afirma instrumentarlo en nativo.

El flujo completo encontró además que `package native:compile` intentaba usar como classpath
el JAR reempaquetado bajo `BOOT-INF`, donde GraalVM no encontraba la clase principal. El parent
ahora configura explícitamente el directorio de clases AOT para el compilador nativo, manteniendo
el JAR ejecutable de Spring Boot. El gate genera un directorio nuevo en cada ejecución y conserva
las evidencias anteriores.

El generador permanente ya produjo otro servicio y sus cinco pruebas aprobaron
(`reactive-native-regression-generated-verify1.log`). Las 24 políticas Python del framework,
actionlint y la revisión de seguridad de 57 archivos aprobaron
(`reactive-native-regression-framework-policies2.log`, `reactive-native-regression-security1.log`).
El entrypoint completo aprobó en Fedora WSL (`reactive-native-regression-full-pipeline2.log`):
instalación de los 159 módulos desde fuente, generación, cinco pruebas JVM sin omisiones,
`package native:compile` y health/liveness/readiness del ELF en `UP`. El fixture final se conserva
bajo `target/reactive-native-acceptance/run.OqSSwU/sample-native-orders`; sus logs y el hash del
binario están en su directorio `target`. La aceptación corresponde a esta combinación reactiva;
no sustituye la matriz nativa de todos los arquetipos ni acredita despliegues OCI remotos.

## Validación integrada de esta etapa

Los 159 módulos aprobaron 1.397 pruebas unitarias y 67 integraciones, sin fallos, errores ni
omisiones (`asyncapi-final-framework-quality2.log`). Incluyó arquitectura, cobertura, formato,
análisis estático y Docker obligatorio. Tools aprobó cuatro módulos y 121 pruebas de recetas,
CLI e Initializr (`asyncapi-final-tools-quality1.log`). Se reutilizó la mutación ya aprobada;
la revisión de 64 reportes no encontró errores de procesos (`asyncapi-final-mutation-health1.log`).
Los ajustes finales del codec y del perfil nativo son posteriores a esa ejecución global:
se verificaron con los controles afectados y el ELF descritos arriba; no se contabilizan
como otra ejecución completa del reactor.

## Migraciones reactivas completas desde 1.0

Se generaron dos proyectos con el CLI real 1.0.0: business/CQRS/Aurora y experience/BFF, ambos
reactivos. Sus pruebas originales pasaron antes de migrar. La versión 1.0 no permite reactive
con layered ni hexagonal; no se construyó una línea base ficticia para esas combinaciones.

El CLI nativo actual ejecutó las recetas reales antes de actualizar los parents y convirtió
CQRS en hexagonal con extensión CQRS, y experience en ux. Ambos proyectos se renombraron y
aprobaron doctor, compilación, arranque, BlockHound y arquitectura, con siete pruebas en total
(`migration-reactive-acceptance/migrated-cqrs-verify1.log`, `migrated-bff-verify1.log`). Se verificó
la conservación del paquete Java, groupId, bytes de OpenAPI y realm OAuth, origen del mensaje,
consumer name, grupo Kafka y aplicación Parameter Store. Las audiencias incluyen los nombres
anterior y nuevo; el chart conserva su directorio y añade el alias de servicio. Sus pipelines
actualizados cumplen los contratos independientes de build y deploy, incluido el disparador
automático, y pasan actionlint. No se cambió la cobertura mínima del esqueleto de origen;
estas ejecuciones funcionales no se presentan como aceptación de `-Pquality` de esos servicios.

Además, `upgrade --dry-run` del binario final conservó los hashes de los 76 archivos originales
de otro proyecto 1.0. El parche completo incluye los pipelines nuevos, omite los POMs generados
y aprobó `git apply --check` (`asyncapi-final-native-migration-dry-run-integrity1.log`).

## Consistencia de las muestras nocturnas

La revisión final encontró referencias v1 y nombres retirados en `native-nightly.yaml` y
`samples-matrix.yaml` del framework. Ambos workflows usan ahora la versión v2 autenticada por
su guard y credenciales Nexus de sólo lectura. La lista nativa contiene los siete servicios
actuales del catálogo: orders, core, gateway, ux, support, notifications-processor y settlement-batch.

`check-sample-workflows.py` verifica todas las referencias internas del framework contra su guard
y compara la selección nativa con los manifiestos reales producidos por `SampleProjects`.
`samples.sh` ejecuta esta comprobación después de generar los defaults; las bibliotecas y starters
se comprueban aparte y no se tratan como ejecutables. CI ejecuta el control y sus seis regresiones,
que rechazan versiones antiguas, guards distintos, credenciales indebidas, módulos retirados,
duplicados, omitidos o fuera del checkout. Las seis pruebas y las otras 17 políticas del framework
aprobaron, junto con actionlint y el control de seguridad
(`sample-workflows-all-framework-policies1.log`, `sample-workflows-actionlint1.log`).
La comprobación contra nueve proyectos generados realmente —siete servicios y dos proyectos
de plataforma— también aprobó (`sample-workflows-generated-defaults1.log`). Esta comprobación
valida la selección del pipeline; no sustituye la compilación nativa de todas las muestras.

## Continuación: servicios bajo agregadores Maven (2 de noviembre)

El lector seguro de reactores pasó del CLI al generador compartido. `ProjectLayout` selecciona
el módulo de aplicación y lo usan generación de artefactos, sincronización OpenAPI/AsyncAPI,
adición de capacidades, renombre, upgrade y doctor. Los metadatos y snapshots permanecen en
la raíz; las dependencias, configuración, fuentes y migraciones se escriben en el servicio.
Con varias aplicaciones, los contratos del manifiesto deben seleccionar una de ellas. Se
rechazan ambigüedades, módulos repetidos/cíclicos, rutas externas, symlinks y entidades XML
antes de aplicar cambios. El lector no ejecuta Maven ni sustituye su modelo efectivo.

Upgrade actualiza todos los parents Axiz declarados y renombra exclusivamente el artifactId
del servicio. Conserva el agregador, las bibliotecas hermanas y los paquetes Java. Doctor
comprueba las fuentes del reactor, pero exige el opt-in BlockHound al módulo de aplicación,
sin imponer instrumentación a una biblioteca Java pura. Los charts conservan su directorio,
también si están dentro del módulo. La migración de workflows fija el módulo, el POM desde el
que leer la versión y el chart existente. Los workflows personalizados y un POM de servicio
con nombre distinto de `pom.xml` requieren revisión: no se aplican automáticamente a un
contrato reutilizable que necesita `<module>/pom.xml`.

El build de servicios construye el módulo y sus dependencias desde el reactor raíz. La versión
final se aplica a los POM y referencias internas de la copia de build, conservando parents del
SDK y dependencias externas. Los artefactos se instalan sólo en el repositorio Maven local del
job para que Jib y el SBOM resuelvan los mismos bytes. Los goals nativos permanecen limitados al
servicio. Build y deploy siguen siendo ejecuciones independientes.

También se corrigió el versionado de los proyectos nuevos: release-please actualiza mediante
selectores XML las versiones propias de los POM del servicio, biblioteca o tres módulos del
starter. No cambia las versiones de parents del SDK ni de dependencias externas. La prueba
permanente usa release-please real sobre las muestras recién generadas, dentro del job de
muestras; dejó de buscar una plantilla JSON que había pasado a Mustache. Aprobaron 54 planes
de release sobre nueve muestras y doce planes sobre los dos proyectos multimódulo migrados.

La aceptación generó un servicio hexagonal/servlet con el CLI real 1.0.0, lo colocó en un módulo
con una biblioteca Java hermana y ejecutó OpenRewrite real hacia 2.0.0. El dry-run conservó
los 82 archivos fuente y produjo un patch aplicable. Después del upgrade, doctor, generación
de una migración Flyway, adición de HTTP client y sincronización OpenAPI aprobaron sin crear
`src` en la raíz. Los hashes de OpenAPI, realm OAuth y SQL inicial permanecieron idénticos;
el source de eventos conserva `urn:axiz:modular-orders` y el despliegue pasa a `payment-orders`.

El reactor migrado aprobó quality con Docker obligatorio y ocho pruebas, incluidas aceptación
de precisión monetaria, paginación, copia defensiva y rechazo de operaciones aún no implementadas.
El fixture incorporó esas pruebas de su código generado; no se redujeron los umbrales del
framework. Un SBOM real generado desde el POM hijo contiene 235 componentes y la biblioteca
hermana con coordenada final `0.1.0`. La evidencia detallada está en
`multimodule-acceptance/RESULTADOS.json`, `multimodule-actual-build4.log` y
`multimodule-actual-sbom2.log`. Esta aceptación local no acredita una publicación ni un deploy remoto.

Las regresiones de políticas aprobaron 24 pruebas del framework, 24 de workflows y 79 del
contrato build/deploy. Las suites finales desde los reactores reales aprobaron 124 pruebas del
motor y 71 del CLI, sin fallos, errores ni omisiones, con cobertura y quality. El motor terminó
con cero hallazgos SpotBugs. El CLI conserva únicamente la advertencia previa sobre
`ProcessBuilder(List)`, revisada porque ejecuta argumentos separados sin shell; no se ocultó
esa advertencia ni se añadieron supresiones generales.

La mutación completa detectó 1.151 de 1.551 mutantes del motor (1.150 muertos y un timeout,
74 %) y 495 de 687 del CLI (493 muertos y dos timeouts, 72 %). Tras simplificar la comprobación
de una lista y fijar el valor nullable del contrato, se repitió además la mutación de las clases
afectadas en copias aisladas: 25 de 41 mutantes en `ContractSync` y 46 de 68 en
`WorkflowMigration`; todos los umbrales aprobaron. La suite final del reactor real repitió las
124/71 pruebas después de esos ajustes. Los 63 reportes de mutación del framework y el del CLI
no contienen errores de trabajadores; esta inspección no representa una nueva ejecución de
PIT sobre todos los módulos del framework.

La estructura sigue siendo consistente en los 159 módulos y la gobernanza de dependencias
aprueba con 70 propiedades de versión. Los contratos verifican 53 consumidores, actionlint
aprueba 83 workflows fuente/generados/migrados y la política de seguridad revisa 57 archivos,
además de los seis workflows de los proyectos multimódulo migrados.
Japicmp se regeneró contra 1.0.0 después de empaquetar los JAR finales: el control exacto
mantiene las 185 rupturas previstas y 161 reubicaciones, sin ampliar excepciones.

El CLI final compiló con GraalVM 25 en Fedora WSL, con límites locales de 4 GiB y cuatro hilos.
Los hashes de sus fuentes y dependencias coinciden con los validados. El ELF aprobó versión,
catálogo, doctor servlet y una segunda migración real: servicio 1.0.0 reactivo/CQRS en un módulo
con biblioteca Java pura. Su dry-run conservó los 88 archivos fuente; el patch es aplicable.
El upgrade real, doctor, generación de SQL y sincronización OpenAPI aprobaron sobre el módulo
hijo. La recompilación aprobó cinco pruebas con PostgreSQL real, arquitectura y BlockHound;
el resultado es hexagonal con extensión CQRS. Se preservaron los hashes de OpenAPI, realm OAuth
y SQL inicial. Un segundo upgrade reconoce la versión actual y conserva la validación doctor.
Contract sync conserva las implementaciones y deja sus propuestas `.axiz-new` para revisión.

El binario validado está disponible en `axiz-tools/axiz-cli/target/axiz`; su SHA-256 es
`162885ba233e053afd412f517e55ac2e444f0f86739a7b1702d463ae258d9848`.
La evidencia está en `multimodule-acceptance/RESULTADOS-NATIVE.json` y
`multimodule-native-final-acceptance1.log`. El sitio se sincronizó con los README actuales y
aprobó MkDocs estricto (`multimodule-docs-build2.log`). Esta continuación valida el alcance
afectado; no vuelve a presentar la ejecución global anterior como una nueva aceptación de todo


1. Completar las capacidades nativas asíncronas restantes, sus fachadas y cableado:
   Kafka (eventos y auditoría). Auditoría SQS/R2DBC, CQRS y credenciales HTTP ya consumen puertos nativos. Validar arranque en
   frío, refresco de credenciales, reconexión, fallo, timeout y cancelación con BlockHound. Completar
   el precalentamiento de pools físicos al arrancar, además de los recursos ya inicializados.
2. Completar los escenarios de migración y convivencia restantes y EDA cuando se complete Kafka
   nativo. Los agregadores con un servicio hijo y biblioteca hermana ya tienen aceptación local.
   La migración completa servlet
   de tres estructuras, las dos combinaciones reactivas descritas, doctor de agregadores y
   propuestas de pipelines ya están comprobados. AsyncAPI tiene evolución compatible,
   records, handlers, binding AOT y comparación de suscripciones; falta la evidencia para autorizar
   la retirada coordinada de tipos antiguos.
3. Cerrar el soporte de snapshots y la aceptación remota de la automatización independiente. Los consumidores
   de framework, tools, demo y plantillas ya están migrados y los exports anteriores se retiraron. Probar la promoción dev/qa/prod y el rollback remoto con las
   credenciales y ambientes configurados. El contrato local se describe en
   `axiz-workflows/docs/pipelines-v2.md`.
4. Migrar los nombres de los nueve servicios de la demo y extraer el gateway independiente,
   conservando las identidades persistentes y verificando replay/reinicio/convivencia.
5. Completar ejemplos, documentación, muestras generadas y aceptación JVM/native. Repetir los
   controles afectados por esas futuras modificaciones; los resultados actuales no aceptan
   automáticamente capacidades todavía pendientes.

El usuario incorporó los cambios anteriores en el commit `f589011`. Los 45 POMs antes pendientes
ya forman parte del checkout. Se corrigió además el modo ejecutable de
`.github/scripts/reactive-native-sample.sh` en el índice: con `core.filemode=false`, un `chmod`
local no bastaba para conservarlo al clonar. El control real de entradas de build aprueba para
framework, tools y demo (`multimodule-build-inputs1.log`, cero problemas), sin un índice temporal
ni excepciones en la política. Esta continuación no crea otro commit.

No se ha publicado una candidata ni se ha ejecutado un despliegue remoto.

## Evidencias de esta continuación

Los resultados detallados permanecen en `/tmp/axiz2-implementation`; este registro dentro del
proyecto conserva el resumen aunque se limpie `/tmp`.

| Comprobación | Evidencia | Resultado |
|---|---|---|
| CLI funcional y calidad | `cli-quality4.log` | 45 pruebas, aprobado |
| Recetas y compilación de fixtures | `recipe-quality11.log` | 26 pruebas, aprobado |
| Locks: fachadas y selección del stack | `lock-quality3.log` | Pruebas y calidad aprobadas |
| Outbox: transacción y relay | `outbox-quality5.log` | 12 pruebas y calidad aprobadas |
| PostgreSQL reactive y SCRAM | `quality-with-pit1.log` | 5 integraciones, aprobadas |
| Mutación y procesos | `quality-with-pit1.log`, `mutation-processes1.log` | Módulos ejecutables aprobados; marcador EDA ajustado |
| API 1.x y reemplazos | `api-gate-final4.log`, `api-codegen-final4.log`, `relocation-api-review.json` | Inventario aprobado; 161 reubicaciones sin pérdida de firmas |
| Regresiones del control de API | `api-gate-tests2.log` | 11 pruebas, aprobado |
| Contrato build/deploy | `build-service-deploy-tests13.log` | 44 pruebas, aprobado |
| Aislamiento de candidatas Maven | `candidate-settings-tests2.log` | 3 pruebas, aprobado |
| Seguridad y separación de workflows | `workflow-policy-tests6.log`, `workflow-security-final2.log`, `workflows-actionlint3.log` | 17 regresiones, revisión y sintaxis aprobadas |
| Imagen y chart OCI reales | `service-oci-roundtrip2.log`, `service-oci-roundtrip.json` | amd64, índice amd64/arm64 y chart aprobados |
| Esquemas GitOps | `gitops-schema2.log` | 3 recursos válidos, 0 omitidos |
| Políticas del framework | `framework-policy-tests1.log` | 17 pruebas, aprobado |
| Generador y snapshot AsyncAPI | `codegen-snapshot-quality1.log` | 100 pruebas y quality, aprobado |
| Sitio de documentación | `docs-build3.log`, `docs-sync5.log` | MkDocs estricto y referencias actualizadas, aprobado |
| Bootstrap del wrapper | `wrapper-bootstrap-tests2.log` | Aprobado |
| Fuente sin artefactos y Maven vacío | `fresh-m2-compile2.log` | 159 módulos, aprobado |
| Cierre global del reactor | `quality-verify6.log` | 159 módulos; 1.190 unitarias y 32 integraciones, aprobado |
| Versionado alternativo del reactor | `version-framework-validate2.log`, `version-framework-compile1.log` | 159 modelos y compilación con quality, aprobados |
| Entradas de un futuro checkout | `checkout-index-proof.log` | Aprobado con índice temporal; índice real sin modificar |

| Continuación nativa | Evidencia | Resultado |
|---|---|---|
| Secuencias: funcionalidad y calidad | `sequence-native-quality6.log`, `sequence-native-quality11.log`, `storage-quality4.log` | Core, kit y Spring aprobados; advertencia de nulabilidad del binder corregida |
| Secuencias: integración real | `sequence-native-integration4.log` | 15 pruebas aprobadas, sin omisiones |
| Mutación de secuencias y dependencias | `sequence-native-pit1.log`, `sequence-mutation-health1.log` | Cuatro módulos con PIT aprobados; módulos Spring pasan sus controles |
| Transporte AWS | `aws-native-transport-quality1.log`, `blockhound-idle-quality2.log` | Admisión acotada, espera ociosa y callbacks vigilados, aprobados |

| Almacenamiento y continuación del transporte | Evidencia | Resultado |
|---|---|---|
| Core storage | `storage-pit1.log` | 12 pruebas, quality y mutación aprobados |
| S3 y transporte finales | `storage-final-pit1.log` | 11 y 17 pruebas; quality y mutación aprobados |
| Fachada y cableado de storage | `storage-spring-quality11.log` | 12 pruebas, cobertura y SpotBugs aprobados |
| Arranque y dependencia opcional ausente | `reactive-bootstrap-quality2.log` | Quality y JVM sin context-propagation, aprobados |
| Diagnóstico acotado de bloqueos ocultos | `blockhound-diagnostics-quality2.log` | 14 pruebas y quality aprobados |
| PostgreSQL, DynamoDB, S3 y TLS | `native-acceptance1.log` | 19 integraciones aprobadas, 0 omisiones |
| Capas y catálogo actualizados | `native-catalog-architecture1.log`, `native-layer-structure1.log`, `native-dependency-governance1.log` | 48 comprobaciones de arquitectura, 159 módulos y gobernanza aprobados |
| Procesos de mutación | `storage-final-mutation-health1.log` | 63 reportes; 0 RUN_ERROR/MEMORY_ERROR/NOT_STARTED/STARTED |
| API de esta continuación | `api-native-gate3.log`, `api-native-refresh1.log`, `api-native-refresh2.log`, `api-native-refresh3.log` | Inventario aprobado antes de repetir el reactor completo |

## Validación independiente (2 de noviembre de 2026)

Revisión del estado posterior al commit `f589011` más los cambios sin commit. Se repitieron los

| Comprobación | Resultado |
|---|---|
| `axiz-framework`: `./mvnw --bootstrap clean install -Pquality` | Aprobado: 159 módulos, 1.469 pruebas (incluidas integraciones con Testcontainers), 0 fallas, 51:56 min |
| `axiz-tools`: `./mvnw clean verify -Pquality` | Aprobado: 124 pruebas, 0 fallas |
| `axiz-workflows`: pruebas Python (venv como `self-check.yaml`) | Aprobado: build-manifest 79, check-workflow-security 24, encapsulation 7, setup-maven 3, sbom 1, mutations 1 |
| Esquema `build-manifest-v1` contra sus fixtures (criterio 1b) | Aprobado: 2 válidos aceptados, 5 inválidos rechazados |
| Scripts del framework (`.github/scripts`) | Aprobado: 24 pruebas |
| `axiz-docs`: `mkdocs build --strict` | Aprobado |
| Grafo de dependencias de los POM (capas 1–4, §7.3) | Sin dependencias hacia arriba, sin adaptador→adaptador (salvo `observability-aws`), sin Spring en capas 1–2, core solo con JDK/SLF4J/JSpecify/Micrometer, parents correctos |
| Nombres 1.x residuales | Solo en las líneas base de japicmp (correcto) |
| `axiz-demo`: `./mvnw clean verify` | **Falla**: el enforcer rechaza adaptadores en `compile` (`payments-experience`; los 8 servicios tienen el mismo problema) |

### Observaciones abiertas

1. **Demo sin migrar (§11).** Los 8 servicios siguen con `schema: 1`, arquetipos 1.x (`crud`,
   `integration`, `process`, `experience`), adaptadores en `compile` y nombres anteriores; no
   existe `payment-network-gateway`; `payments-history` aún depende de `axiz-structure-eda-api`;
   BlockHound solo en 1 prueba por servicio reactivo. Solo la separación `ci.yaml`/`deploy.yaml`
   está hecha.
2. **DoS al parsear `Money` (seguridad).** `Money.parseAmount` y la rama de texto de
   `MoneyJacksonModule` crean `new BigDecimal(texto)` sin límite de longitud y validan el rango
   después. Medido en JDK 25: 1.000.000 de dígitos tarda 12,4 s; 5.000.000 no termina en 2 min.
   Corrección: rechazar textos de más de ~`MAX_INTEGER_DIGITS + MAX_SCALE + 10` caracteres antes
   de construir el `BigDecimal`.
3. **Regla faltante de §7.5.** Ninguna regla de `axiz-archunit-rules` impide que un servicio
   importe clases `*AutoConfiguration` (sí existen las de adaptadores y kits).
4. **`HexagonalRules` con lista manual de adaptadores.** Enumera 15 paquetes `pe.axiz.adapter.*`
   (faltan audit, security, sequence y observability) y conserva `pe.axiz.core.persistence..`, que
   ya no existe. Debería usar `pe.axiz.adapter..` y `pe.axiz.kit..`.
5. **Nombres de reglas de `FrameworkArchitectureTest`.** Siguen como `SPRING_DOES_NOT_KNOW_FRAMEWORKS`,
   `*_STRUCTURES`. La regla de APIs no verifica "sin otro `-api` de estructura" (salvo
   cqrs→hexagonal) ni existe `CORE_DOES_NOT_KNOW_TECHNOLOGY` con ese nombre (lo cubre
   `CORE_IS_PLAIN_JAVA`). Los ciclos solo se verifican en `structure`, no en `extension`.
6. **Resto obsoleto.** `frameworks/README.md` sigue versionado y describe el modelo 1.x.
7. **Desviación documentada pendiente.** Los `-api` de capa 4 usan `axiz-parent-api` (más estricto)
8. **Calidad menor.** `PaymentsComposition` valida el monto después de consultar el beneficiario
   (debería validar antes de cualquier I/O) y usa `java.math.BigDecimal` calificado; ~600 usos de
   nombres calificados `java.util.*` en el código (incluidas pruebas); 21 pruebas con `Thread.sleep`;
   `LockTemplate.renewLoop` no restaura la interrupción (inocuo porque es su propio hilo).
