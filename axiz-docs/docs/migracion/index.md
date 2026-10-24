# Migración entre versiones

Cada versión del framework publica sus **recetas de OpenRewrite** (`pe.axiz:axiz-rewrite-recipes`,
misma versión) y una guía en esta sección.

## Cómo actualizar un servicio

1. Renovate abre un PR que solo cambia el parent (`axiz-parent-service`) con la nota de la versión.
2. En ese branch:

    ```bash
    axiz upgrade --to 1.3.0 --dry-run   # muestra el diff de las recetas sin cambiar nada
    axiz upgrade --to 1.3.0             # parent, manifiesto y recetas
    ./mvnw verify
    ```

3. Revisa el diff, ajusta lo que la guía de la versión marque como manual y sube el PR.

`axiz upgrade` actualiza la versión en el `pom.xml` y en `.axiz/project.yaml` y ejecuta
`rewrite:run` con las recetas de la versión destino (`--skip-rewrite` solo cambia versiones). Las
recetas encadenan las de versiones intermedias, así se puede saltar de minor en minor.

## Política

- Se soportan la versión actual y la minor anterior.
- Lo obsoleto se marca `@Deprecated(forRemoval = true)` al menos una minor antes de borrarse, y la
  receta de esa versión hace el reemplazo.

## Guías por versión

### 0.1

Primera versión: no hay migración. Los servicios creados con versiones previas a la publicación
(prototipos) se regeneran con `axiz new` sobre su contrato y se mueve el código de negocio.

#### Cambios de la revisión (respecto del paquete preliminar 0.1.0 con R2DBC)

Si ya creaste tablas o stores propios con el paquete preliminar:

- **Inbox (JDBC y R2DBC):** la tabla tiene la columna del dueño de la concesión.

    ```sql
    ALTER TABLE axiz_inbox ADD COLUMN owner VARCHAR(64);
    ```

    Las filas existentes sin dueño se comportan como concesiones de una entrega desconocida: se
    retoman cuando vence su `lease_until`.

- **Stores propios de idempotencia o inbox:** `IdempotencyStore.complete` y `release`, y
  `InboxStore.markProcessed` y `release`, reciben el token de dueño y devuelven `boolean`. Valida
  la implementación con las comprobaciones de `axiz-core-store-contracts`
  (`IdempotencyStoreContract`, `InboxStoreContract`).
- **`IdempotencyService`:** `complete` y `fail` reciben el `Proceed` devuelto por `begin`. Las
  llaves guardadas antes del cambio usaban otro alcance: se ignoran y vencen solas.
- **`RequestMetadata`** suma el componente `issuer` (emisor del token); usa el builder o
  `withCaller(clientId, subject, tenant, issuer)` en lugar del constructor.
- **Wrapper de Maven:** verifica la distribución antes de usarla. Fija el checksum oficial una vez
  con `./mvnw --pin-checksum` y haz commit de `.mvn/wrapper/maven-wrapper.properties`; los releases lo
  exigen.

#### Cambios de la segunda revisión


- **Llave del inbox = `source` + `id`.** Las registraciones del paquete preliminar usaban solo el
  `id`, así que no coinciden con las nuevas. Un mensaje ya procesado que se vuelva a entregar
  durante la ventana de retención se procesaría otra vez. En instalaciones del paquete preliminar
  hay dos opciones:
  - parte de un inbox vacío, sin mensajes pendientes de reentrega; o
  - deja que los handlers idempotentes del negocio absorban la ventana.
- **Esquema del inbox:** si tu tabla no tiene `owner`, el servicio no arranca y el error indica la
  migración. Crea una **nueva** migración con el contenido de
  `axiz/sql/inbox-postgresql-upgrade-owner.sql` y nunca edites una que ya se aplicó.
- **Idempotencia HTTP:**
  - `IdempotencyInterceptor` desaparece: ahora la idempotencia corre como advisor después de
    `@PreAuthorize`. Si registrabas el interceptor a mano, quítalo.
  - `IdempotencyFilter` e `IdempotencyWebFilter` reciben
    `(service, maxBodySize, maxResponseSize)`.
  - `IdempotencyService.fingerprint` usa la query tal como llega, sin reordenarla. Las llaves
    guardadas con la huella anterior pueden responder 422 a un reintento dentro de su retención.
- **Stores en memoria:** `axiz.idempotency.store=memory`, `axiz.lock.store=memory` y
  `axiz.storage.type=memory` solo se aceptan con `axiz.config.environment` en `local`, `test` o
  `ci`. Los ambientes desplegados necesitan Redis, DynamoDB o S3 (ver
  [Perfil operativo](../operacion/despliegue.md#perfil-operativo)).
- **Autorización por recurso:** declara una `AccessPolicy` para cada comando o consulta que nombre
  recursos de un cliente, o marca con `@OpenAccess(reason)` los internos. Luego define
  `axiz.cqrs.authorization.unprotected: fail`. El valor por defecto `warn` lista en el log lo que
  falta cubrir. En servicios reactivos, las políticas que leen estado implementan
  `ReactiveAccessPolicy`.
- **Sagas:**
  - Existe el estado `IN_DOUBT` y el método `SagaListener.inDoubt` (con implementación por
    defecto).
  - Un paso que no implementa `verify` deja la saga en `IN_DOUBT` ante un fallo que no es un
    rechazo definitivo. Antes compensaba.
  - Lanza `SagaStepRejectedException`, o un error 4xx de negocio, cuando el participante rechazó
    con certeza.
  - Si tu tabla de sagas tiene un `CHECK` sobre `status`, añade `IN_DOUBT`.
- **Auditoría durable:** `axiz.audit.durable.enabled: true` requiere `axiz-core-outbox` y un
  destino `audit` en `axiz.messaging.destinations`.
- **Demo de pagos:**
  - `PaymentInstruction` suma `customerId` y `MovementRequest` suma `holderId`.
  - El core bancario rechaza débitos de cuentas ajenas (`CB-17`).
  - payments-business suma la migración `V3` (reservas diarias y `request_key`), y
    payments-history suma `V3__inbox_owner.sql`.
- **CI:** los workflows de librería publican en dos pasos. `lib-publish` con `stage: true` sube a
  un repositorio temporal y `lib-promote` publica cuando pasaron todos los gates. Un repositorio
  que llame a `lib-publish` directamente debe pasar a ese esquema; ver
  [CI/CD](../operacion/ci-cd.md).
- **Tokens de aplicación:** `ClaimMapping` suma el parámetro `machine` y
  `axiz.security.claims.machine` define las marcas de los tokens de client credentials. Los
  valores por defecto cubren Keycloak y Microsoft Entra. Revisa la guía de
  [seguridad](../guias/seguridad.md#resource-server) si usas otro proveedor. Un realm de Keycloak
  importado con scopes propios debe declarar el scope `basic` (claim `sub`) y el mapper de
  `preferred_username`, como `axiz-demo/local/keycloak/axiz-realm.json`.
- **Build local y gate de CI:** `./mvnw verify` ya no corre Checkstyle, Error Prone + NullAway
  ni la convergencia de dependencias; esos controles se corren con `-Pquality`, que es lo que
  ejecutan los workflows compartidos, y también la revisión de formato (Spotless). Ejecuta
  `./mvnw spotless:apply` antes de subir cambios. Spotless usa google-java-format 1.30, la primera
  versión que corre sobre JDK 25, y dejó de ordenar los POM (sortpom).
  `-Daxiz.errorprone.skip` ya no existe.
- **`StorageRecordItemReader`:** se crea con `StorageRecordItemReader.named(nombre, storage, key,
  formato)`. El constructor ya no recibe el nombre, porque fijarlo dentro del constructor expone
  el objeto antes de terminar de construirse (advertencia `this-escape`).
- **MapStruct:** los mappers declaran `componentModel` y `unmappedTargetPolicy` en `@Mapper`; el
  parent ya no pasa opciones `-Amapstruct.*` al compilador.
- **Modelos OpenAPI:** el parent reemplaza en el código generado `org.springframework.lang.Nullable`
  (deprecado en Spring 7) por `org.jspecify.annotations.Nullable`.
- **Persistencia de los estilos:** `axiz-framework-layered-spring-boot-starter` ya no trae
  `axiz-jpa-spring-boot-starter`, y `axiz-framework-eda-spring-boot-starter` ya no trae
  `spring-boot-starter-jdbc` (solo la configuración del outbox). Un pool de conexiones en el
  classpath hace que Spring Boot exija una base de datos, así que un servicio sobre DynamoDB no
  arrancaba (`Failed to configure a DataSource`). Los servicios con Aurora ya declaran
  `axiz-jpa-spring-boot-starter` (capacidad `jpa`). Si un servicio tuyo usaba JPA o el outbox sin
  declararlos, agrega `axiz-jpa-spring-boot-starter` o `axiz-outbox-spring-boot-starter`.
- **Seguridad en EDA:** `axiz-framework-eda-spring-boot-starter` ya no trae
  `axiz-security-spring-boot-starter`. Un procesador de eventos no tiene API, y sin
  `axiz.security.issuers` el resource server impedía arrancar. Si tu procesador expone endpoints
  propios, declara el starter de seguridad y sus emisores. Con la lista vacía, el error ahora dice
  qué configurar (`axiz.security.issuers is empty: ...`).
- **Token relay reactivo:** los clientes reactivos (`WebClient`) con `auth.type: TOKEN_RELAY` toman
  el token entrante del contexto de seguridad de Reactor. Antes lo buscaban en el contexto del
  hilo, que en WebFlux está vacío, y la llamada salía sin `Authorization`. No requiere cambios en
  los servicios.
- **Errores en WebFlux:** los servicios reactivos ya no quedan sin responder cuando ocurre un
  error: el traductor de excepciones solo carga las clases del API de servlets si están
  presentes, y `ProblemFactory` omite un traductor que falle (su error queda como `suppressed`
  en la excepción original). Las fallas de red de `WebClient` (timeout, conexión rechazada o
  cortada) llegan como `ExternalServiceException` (503), igual que con `RestClient`.
- **Versión de API por defecto:** con `axiz.web.api-version.enabled: true` y el header opcional, la
  versión de una request sin header es `default-version` o, si falta, la menor de `supported`. En
  Spring WebFlux 7.0, sin versión por defecto, una request sin el header no encuentra ninguna ruta
  (404). Si el header es opcional y no hay ni `default-version` ni `supported`, el servicio no
  arranca. Los servicios experience generados declaran `default-version: "1"`.
- **Idempotencia en WebFlux:** `IdempotencyWebFilter` registra la respuesta en un worker
  independiente de la request, al terminar de escribirla. El servidor cancela el trabajo de una
  request cuando el cliente cierra la conexión, y un registro hecho en ese trabajo se
  interrumpía: la llave quedaba `IN_PROGRESS` hasta vencer. Si la escritura no termina, se
  registra el resultado sin cuerpo, porque la operación ya tuvo efecto. Sin cambios en los
  servicios.
- **Destinos `kafka:<topic>`:** `KafkaEventPublisher` quita el prefijo `kafka:` del destino; antes
  publicaba en un topic llamado `kafka:<topic>`, inválido en Kafka. Los nombres de topic sin
  prefijo siguen funcionando.
- **Consumidores Kafka y SQS:** los listeners se crean cuando `axiz.messaging.kafka.topics` (o
  `axiz.messaging.sqs.queues`) tiene elementos, se escriba como lista YAML, con índices o separado
  por comas. Antes la condición solo veía la forma separada por comas: con una lista YAML no se
  creaba ningún consumidor, el servicio arrancaba sin consumir y los eventos quedaban en el topic.
  Sin cambios en los servicios.
- **Enmascaramiento solo en respuestas:** `@Sensitive` y `@Mask` se aplican en el conversor JSON de
  las respuestas del servidor (servlet) o en su encoder (reactivo), ya no en el `JsonMapper` de la
  aplicación. Antes el módulo estaba en ese mapper, que también serializa los payloads de eventos,
  el contexto de las sagas, la caché, los resultados idempotentes y los cuerpos de las llamadas
  salientes: una cuenta marcada viajaba enmascarada en el evento. Si serializas con el `JsonMapper`
  inyectado y necesitas el valor enmascarado, registra `SensitiveJacksonModule` en una copia del
  mapper.
- **Scheduler:** `axiz-scheduling` se configura antes que el `TaskScheduler` de Spring Boot, así que
  las tareas `@Scheduled` usan el scheduler de Boot (hilos virtuales, apagado ordenado) en vez de un
  scheduler privado de un hilo. Sin cambios en los servicios.
- **Trazas a través del outbox:** `OutboxWriter` guarda con cada evento el contexto de traza de la
  escritura (extensión `outboxtraceparent`), y `ObservedEventPublisher` lo continúa al publicar y lo
  quita antes de enviarlo. La request que escribe en el outbox, la publicación y los consumidores
  quedan en una sola traza. Aparecen dos observaciones nuevas, `axiz.outbox.write` y
  `axiz.outbox.dispatch` (spans y timers). Sin cambios en los servicios. Si tienes un
  `EventPublisher` propio en lugar del de axiz, quita esas extensiones antes de publicar.
- **Clientes AWS con endpoint y región:** la auditoría por SQS, Secrets Manager, Parameter Store,
  KMS y los stores en DynamoDB (locks, inbox, idempotencia, secuencias, ShedLock) creaban su
  cliente con `XxxClient.create()` cuando no había un bean: ignoraban `axiz.aws.endpoint` y la
  región, iban a AWS real desde local (`403 The security token included in the request is
  invalid`) y no se observaban. Ahora todos usan `AwsClients.configured(...)`, que lee
  `axiz.aws.region` y `axiz.aws.<servicio>.endpoint` o `axiz.aws.endpoint`, y se registran como
  beans que se cierran al apagar. Los adaptadores AWS (`axiz-core-*-sqs`, `-sns`, `-s3`,
  `-dynamodb`, `-kms`, `axiz-core-secrets-aws`) traen `axiz-core-observability-awssdk`; antes un
  servicio sin el starter de DynamoDB fallaba con `NoClassDefFoundError: AwsClients` al crear el
  cliente de S3. Sin cambios en los servicios.
- **`protobuf-java` 4.36.2:** `axiz-dependencies` fija esta versión (la exportación de métricas
  OTLP la trae por `opentelemetry-proto`). Hasta la 4.35 el runtime llamaba a métodos de
  `sun.misc.Unsafe` marcados para eliminación y Java 25 lo avisaba al arrancar cada servicio.
- **Endpoint de trazas:** usa `management.opentelemetry.tracing.export.otlp.endpoint`.
  `management.otlp.tracing.endpoint` es de Spring Boot 3 y en Boot 4 se ignora; los servicios
  generados y el demo ya usan la nueva.
- **Clientes HTTP observados en los dos stacks:** los grupos de `axiz.http-client` reciben el
  `ObservationRegistry` de la aplicación también en `WebClient`. Sin `spring-boot-starter-webclient`
  (módulo aparte en Boot 4) las llamadas de un servicio reactivo salían sin span ni `traceparent`
  y la traza se cortaba en el primer salto. Los servicios reactivos con clientes HTTP declaran ese
  starter (los generados ya lo hacen).
- **Nombres de bean únicos:** `GeneralRules.UNIQUE_BEAN_NAMES` (en el `ArchitectureTest` de cada
  servicio) falla si una clase `@Configuration`/`@Component` se llama como un método `@Bean` u otro
  componente (`SettlementJob` con `Job settlementJob()`): compila y pasa las pruebas unitarias, pero
  el contexto no arranca. Renombra la clase (por ejemplo `SettlementJobConfiguration`). La regla
  encontró el mismo error en el generador: `axiz generate command x` y `axiz generate query x`
  creaban dos clases `XHandler` (en `application/command` y `application/query`) y el servicio no
  arrancaba. Ahora se llaman `XCommandHandler` y `XQueryHandler`; si ya tienes ese par, renombra
  una.
- **Logs:** los stores en memoria (idempotencia, locks), permitidos solo en `local` y `test`, se
  informan en INFO, no en WARN; los conflictos (409) se registran en INFO y los accesos denegados
  siguen en WARN. Las conexiones Redis propias de axiz las cierra el `RedisClient` de la fábrica
  de conexiones (Lettuce ya no avisa `Connection is already closed` al apagar). El log de cada
  request incluye el header `traceparent`.
- **Dependencia que no contesta:** un timeout de un cliente HTTP (`RestClient`, `WebClient`,
  `SoapClient`) es ahora una `ExternalServiceException` 504 (`AXZ-EXT-0504`); una conexión
  rechazada o reiniciada, un circuito abierto o un bulkhead lleno siguen en 503. Antes los
  clientes REST respondían 503 también ante un timeout. El mensaje dice
  `Call to <sistema> got no response (<tipo>)` en vez de `failed with status -1`, y el cliente
  servlet conserva la causa. Para las sagas, 503 y 504 siguen siendo un resultado incierto. Si
  un cliente tuyo del servicio esperaba 503 ante un timeout, acepta también 504.
- **Log de errores de dependencias:** `ProblemResponder` registra una falla de dependencia (5xx de
  tipo `EXTERNAL`) en WARN, con `cause=<tipo>: <mensaje>` y sin stack trace; las fallas internas
  siguen en ERROR con su stack trace. Si una alerta contaba los ERROR de `axiz.errors` para
  detectar dependencias caídas, cámbiala a la métrica `http.client.requests` (ver
  [Errores](../guias/errores.md#que-se-registra)).
- **Acceso nativo:** `axiz-parent-service` concede acceso nativo al classpath
  (`Enable-Native-Access: ALL-UNNAMED` en el manifiesto del jar y `--enable-native-access` en la
  imagen de Jib y en las pruebas). Netty lo necesita para su transporte nativo; sin él, Java 25
  avisa al arrancar y una versión futura lo bloqueará. Si un servicio arranca con un comando
  propio (`java -cp ...` sin `-jar`), agrega `--enable-native-access=ALL-UNNAMED`.
- **Initializr:** el registro OAuth `github` usa el callback `{baseUrl}/authorized/github` en vez
  del de login. Actualiza la URL de callback de la OAuth App de GitHub de cada ambiente
  (`https://<initializr>/authorized/github`). Solo el registro `sso` inicia sesión, y
  `POST /v1/projects/github` sin autorización de GitHub responde 403 con `authorize` en vez de
  redirigir.

#### Cambios de la tercera revisión (auditoría de código)

- **Pruebas de integración (Failsafe):** `axiz-parent-service` fija `classesDirectory` de Failsafe
  en `target/classes`. Después de `spring-boot:repackage` el jar del módulo es ejecutable y sus
  clases están en `BOOT-INF/classes`, así que las pruebas `*IT` no encontraban las clases del
  servicio. Sin cambios en los servicios.
- **Parameter Store:** `spring.cloud.aws.parameterstore.enabled` sigue a
  `axiz.config.parameter-store.enabled` (falso por defecto). Antes Spring Cloud AWS creaba un
  `SsmClient` al arrancar aunque nadie lo usara, y sin región de AWS (pruebas, laptops, proyectos
  generados sin AWS) el contexto no arrancaba. Un valor explícito del servicio gana.
- **Checkstyle 14.2:** `JavadocStyle` ya no existe (se eliminó en Checkstyle 13.9). Las reglas de
  axiz usan `SummaryJavadoc` sin exigir punto final: un Javadoc necesita una descripción, no solo
  tags. Si tus `suppressions.xml` nombran `JavadocStyle`, cámbialo por `SummaryJavadoc`.
- **NullAway y JSpecify:** `axiz-dependencies` fija JSpecify 1.0.1, la versión que trae Micrometer
  1.17 (la regla `RequireUpperBoundDeps` fallaba con 1.0.0). `axiz-observability` excluye
  `aopalliance:aopalliance`, cuyas clases ya trae `spring-aop` (fallaba `BanDuplicateClasses`).
- **Idioma inglés:** el framework incluye `axiz/i18n/messages_en.properties`. Con el idioma por
  defecto `es-PE`, una request en inglés recibía el texto en español, porque el bundle raíz se
  consulta después del idioma por defecto. Cada bundle de un servicio debe tener un archivo por
  idioma soportado (`messages_en.properties`, `messages.properties` para el idioma por defecto),
  como los servicios generados.
- **Parámetro `sort` inválido:** responde 400 con la violación del campo `sort`, en los dos stacks.
  Antes respondía 500.
- **Duraciones de EDA y BFF:** `axiz.eda.handler-timeout` y `axiz.bff.timeout` se leen con el
  binder de Spring Boot (`500ms`, `30s`, `PT30S`). Antes dependían del servicio de conversión del
  `Environment`: en una aplicación Boot funcionaba, pero en un contexto sin él (por ejemplo
  `ApplicationContextRunner` en pruebas) un valor configurado impedía arrancar
  (`No converter found ... Duration`). Sin cambios en los servicios.
- **Initializr:** los tokens bearer del API deben traer la audiencia `axiz.initializr.audience`
  (`axiz-initializr` por defecto, variable `AXIZ_INITIALIZR_AUDIENCE`). Un token del mismo SSO
  emitido para otro API recibe 401. En el proveedor de identidad, el cliente que usa la CLI
  necesita un mapper de audiencia con ese valor; el realm local lo trae.
- **Demo, liquidación:** `settlement.run-on-startup` (verdadero por defecto) permite arrancar el
  servicio sin cargar un archivo, y `SettlementRunner.load(key)` carga uno bajo el lock. Al volver
  a ejecutar un archivo ya cargado, si falta su resumen en S3 se escribe otra vez desde las filas
  de Aurora (LocalStack no conserva datos entre reinicios y PostgreSQL sí).
- **Pruebas con R2DBC falso:** `FakeR2dbc.Execution` registra también el tipo de cada parámetro
  (`types()`), incluido el de los `Parameter` de R2DBC y los `bindNull`.

#### Cambios de la cuarta revisión (tres auditorías independientes)

Cambios que un servicio existente debe conocer al actualizar:

- **Configuración por archivos, sin API de Kubernetes:** `axiz-config` ya no usa Spring Cloud
  Kubernetes. El chart monta los ConfigMaps `axiz-common` (opcional), `<servicio>` y
  `<servicio>-errors` en `/etc/axiz/config/{common,service,errors}` y el servicio los importa
  como archivos. El pod ya no monta token de ServiceAccount y el chart ya no crea `Role` ni
  `RoleBinding`. Acción: copia en tu chart `templates/_helpers.tpl`, `deployment.yaml`,
  `networkpolicy.yaml`, `configmap*.yaml` de un servicio recién generado con la misma versión
  (`axiz new` en un directorio temporal), borra `templates/rbac.yaml` y agrega a `values.yaml`
  `serviceAccount.automountToken: false` y `sharedConfigMap: axiz-common`. Si tu servicio leía otros
  ConfigMaps por la API, móntalos como archivos.
- **NetworkPolicy con salida restringida:** además de la entrada, el tráfico de salida queda
  denegado salvo DNS, los pods del namespace y lo que declares en `networkPolicy.egress`
  (`namespaces` y `cidrs` con puertos). Acción: en cada `values-<env>.yaml` lista los CIDR de los
  endpoints de VPC de AWS, Aurora, ElastiCache, MSK y los socios que el servicio llama.
- **Seguridad por defecto:** OpenAPI, Swagger UI y `/actuator/info` quedan cerrados; ábrelos con
  `axiz.security.public-api-docs` o `public-info` solo donde corresponda (los proyectos generados
  lo hacen en el perfil `local`). Un token de un emisor no confiable recibe 401.
- **Rate limit por regla:** cada regla acepta `fail-open`; define `false` en las que mueven dinero.
- **Caché, llaves versión `k2`:** la llave incluye la firma del método y el tipo de cada
  argumento; argumentos sin identidad estable (objetos arbitrarios) se rechazan. Para compartir
  entradas entre métodos usa `@AxizCacheable(keyName = ...)`. Las entradas viejas no se leen y
  expiran por su TTL.
- **`RequestSignature` versión 2:** nueva API con `Message`, llaves por id, nonce y `NonceStore`
  (`RedisNonceStore` en clúster); `verify` devuelve un `Outcome`. El formato firmado cambió
  (`AXIZ-HMAC-V2`), así que firmante y verificador se actualizan juntos.
- **WS-Security:** `password-mode` ya no tiene valor por defecto y `DIGEST` se llama
  `LEGACY_SHA1_DIGEST`; con credenciales el endpoint debe ser `https://` (`allow-plain-http: true`
  solo para sustitutos locales).
- **Tarjetas:** `CardBrand.of` pasa a `CardBrand.guess` (solo para mostrar). Las decisiones de
  ruteo, autorización o riesgo usan `CardSchemeResolver` con una `IinRangeTable` cargada desde la
  tabla IIN/BIN gobernada.
- **Tipos inmutables:** `EventEnvelope`, `StoredResponse`, `DataKeyProvider.DataKey` y
  `SoapEnvelope.Credentials` dejaron de ser records (contenían arreglos): mismos constructores y
  accesores, `equals` por contenido; ya no admiten patrones de record.
- **Contraseñas:** `Pbkdf2PasswordHasher` exige entre 100 000 y 10 000 000 iteraciones.
- **Pruebas:** `EphemeralSecrets` (en `axiz-test-spring-boot-starter`) genera las llaves de prueba
  en cada ejecución; borra los archivos de llaves versionados en `src/test/resources`.
- **ArchUnit:** las reglas generales prohíben locks de la JVM (`java.util.concurrent.locks`,
  métodos `synchronized`) en el código de negocio; usa `LockTemplate`.
- **Feature flags por OpenFeature:** una bandera que no está configurada devuelve el valor por
  defecto que pasó quien la consulta, con `FLAG_NOT_FOUND`, en lugar de `false`. Revisa las
  llamadas `getBooleanValue(nombre, true)`: antes recibían `false` si la bandera no existía.
- **`ddl-auto` solo con perfiles `local` o `test` exactos:** un perfil cuyo nombre solo contiene
  esas palabras (`testing`, `localstack-prod`) ya no permite `create`, `create-drop` ni `update`.
- **CI/CD:** los workflows se usan por `@v1.0.0` (Renovate los fija por SHA), la firma de imágenes
  es obligatoria, qa y prod se despliegan solo por digest, la guardia de nombres
  (`AXIZ_NAMING_GUARD`) es obligatoria y el wrapper de Maven trae el SHA-512 fijado.

