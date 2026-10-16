# Verificación de esta entrega

El entorno donde se construyó no tenía acceso a Maven Central, así que `./mvnw verify` no pudo
ejecutarse aquí: corre en CI (`.github/workflows/ci.yaml`, que además genera y construye un
servicio por arquetipo). Localmente se verificó con `javac` (JDK 21 con `--release` compatible) y
JUnit Jupiter, usando las fuentes o JARs reales de Spring Boot 4.1, Spring 7, Jackson 3, AWS SDK v2,
Kafka, Lettuce, Reactor, R2DBC (SPI, pool, PostgreSQL), Spring Data R2DBC, Nimbus, ShedLock y
ArchUnit, y stubs de compilación generados solo para
dependencias opcionales de esas librerías (nunca para código de axiz).

## Resultado

| Qué | Resultado |
|---|---|
| Módulos del framework | 139 OK, 2 omitidos (ver abajo); el reporte de cobertura es `pom` |
| Pruebas unitarias ejecutadas | 442 OK, 0 fallidas (capa 1, APIs de los estilos, codegen, reglas ArchUnit), más 9 del store Redis ejecutadas compilando Lettuce desde fuentes |
| Auto-configuraciones y starters de estilos (capa 2/3) | 43 compilan código y pruebas; las pruebas de contexto (`ApplicationContextRunner`) se ejecutan en CI |
| Starters (solo dependencias), plantillas, archetypes | 36 verificados por estructura |
| Proyectos generados (todas las combinaciones válidas: 27 servicios + library + starter, incluidos `business · cqrs · reactive · aurora` y `event-processor · eda · reactive · aurora` sobre R2DBC) | compilan main y test; `ArchitectureTest` OK en los 27 servicios; pruebas unitarias de los generados reactivos con Aurora OK |
| Charts generados y del demo (105 renders: cada chart × dev/qa/prod) | kubeconform estricto (Kubernetes 1.33) y kube-linter 0.7.6 sin hallazgos |
| Workflows | actionlint + shellcheck sin hallazgos; acciones de terceros fijadas por SHA |
| Reglas del framework (`FrameworkArchitectureTest`) | 6 reglas OK sobre todas las clases compiladas (capa 1 Java puro, `-api` sin Spring ni Reactor, starters aislados, sin ciclos) |
| R2DBC (Aurora en reactivo) | capa 1 con pruebas ejecutadas sobre un driver R2DBC en memoria (`FakeR2dbc`): SQL con parámetros nombrados, transacciones, outbox con `SKIP LOCKED`, inbox, tokens IAM; `ReactiveTypedEventHandler` probado con Reactor real; el bus reactivo de CQRS probado aparte (orden del pipeline, ejecución diferida y contexto) |
| POMs (framework, tools, demo y los 29 proyectos generados) | chequeo estático de lo que Maven valida al leer los POM: toda dependencia y plugin sin versión está gobernado por `axiz-dependencies`, los BOM que importa o el `pluginManagement`; sin dependencias duplicadas. Lo agregamos después de que `./mvnw install` fallara fuera de este entorno por `nimbus-jose-jwt` sin versión |
| Classpath declarado | cada import de cada módulo (framework, tools, demo y los 29 proyectos generados, código y pruebas) se resolvió contra las dependencias que el módulo declara y sus transitivas de compilación, tomadas de los POM y build files de las librerías. Encontró `slf4j-api` sin declarar en 5 autoconfiguraciones (el error que apareció en `./mvnw install`), handlers generados sin mensajería y entidades JPA generadas en servicios reactivos; todo corregido. La compilación local anterior usaba un classpath único y no lo detectaba |
| Advertencias del compilador | framework, tools, demo y los 29 proyectos generados compilan con `-Xlint:all` (sin `processing`, `serial`, `try`) contra las fuentes reales de Spring, Jackson, Reactor y el SDK de AWS: cero advertencias, ninguna deprecación. La regla ArchUnit `NO_DEPRECATED_API` lo mantiene en cada `ArchitectureTest` |
| Persistencia de los starters de estilo | `check-dependency-governance.py` recorre las dependencias que hereda cada servicio y falla si un starter de estilo trae un pool de conexiones. Lo agregamos después de que `beneficiaries-crud` (layered sobre DynamoDB) no arrancara fuera de este entorno (`Failed to configure a DataSource`): el starter layered traía JPA y el de EDA `spring-boot-starter-jdbc`. Con la versión anterior la regla marca justo esos dos; con la actual pasa. Después, `notifications-processor` falló por el resource server que el starter de EDA incluía sin emisores configurados: el starter ya no lo trae y el error de emisores vacíos indica qué configurar (prueba en `SecurityTest`). Se revisaron las validaciones de arranque de todas las auto-configuraciones contra la configuración de cada servicio del demo. El arranque real de cada servicio lo cubren las pruebas `*ApplicationTest` en CI (Docker) |
| Token relay reactivo | `AxizExchangeFilter` toma el token entrante del contexto de seguridad de Reactor (antes lo buscaba en el contexto del hilo, vacío en WebFlux). Prueba en `HttpClientTest` (compila sin advertencias; corre en CI con Spring Security) |
| Errores en servicios reactivos | Reproducido con clases propias: un `instanceof` contra una excepción que hereda de una clase ausente lanza `NoClassDefFoundError` (el error de `payments-experience`); aislado en una clase anidada que solo se carga si el API de servlets existe, no falla. `ProblemFactory` omite un traductor que falle (prueba en `ErrorsTest`, ejecutada). `WithoutServletApiTest` carga los traductores sin `jakarta.servlet` y `HttpClientTest` cubre la traducción de fallas de red reactivas (corren en CI) |
| Versión de API por defecto | Leído en las fuentes de Spring WebFlux 7.0.9: sin header y sin versión por defecto, `resolveParseAndValidateApiVersion` termina vacío y `AbstractHandlerMapping` no busca handler (404 en todas las rutas; era el 404 del pago). La lógica de la versión efectiva se ejecutó aparte (por defecto configurada, menor soportada con orden numérico, error sin ninguna) y queda en `WebTest` |
| Idempotencia reactiva | `IdempotencyWebFilter` registra la respuesta una sola vez, en un worker independiente de la request, al terminar la escritura (si la escritura no terminó, sin cuerpo); el log de `payments-experience` mostró el registro interrumpido (`RedisCommandInterruptedException`) cuando el cliente cerraba la conexión. Compila sin advertencias; el flujo completo corre en CI y en el smoke |
| Destinos de Kafka | `KafkaEventPublisher` con destinos `kafka:<topic>` publica en `<topic>`: prueba nueva y la existente ejecutadas con el `MockProducer` real de kafka-clients. El log del demo mostró `INVALID_TOPIC_EXCEPTION` para `kafka:payments` |
| Consumidores de mensajería | Con `axiz.messaging.kafka.topics` escrito como lista YAML, la condición `'${axiz.messaging.kafka.topics:}' != ''` resolvía `''` (comprobado con el `StandardEnvironment` de Spring compilado desde fuentes) y no se creaba ningún listener: el log de `payment-process` no tenía `ConsumerConfig` y el pago quedaba en `RECEIVED`. `@ConditionalOnListProperty` enlaza la lista con el `Binder` de Spring Boot; `ListPropertyConditionTest` (lista, comas, vacía) se ejecutó contra el `Binder` real |
| Enmascaramiento solo en respuestas | El módulo de `@Sensitive` estaba en el `JsonMapper` de la aplicación, que también serializa eventos, sagas y caché: ejecutado con Jackson 3 compilado desde fuentes, el payload `{"debtorAccount":"1910012345678"}` salía `*********5678`. Ahora solo el conversor o encoder de las respuestas del servidor enmascara; la misma ejecución muestra el mapper de la aplicación sin cambios y el de respuestas enmascarado (`SensitiveTest`) |
| Trazas a través del outbox | Ejecutado con los handlers reales de Micrometer Tracing (`PropagatingSender/ReceiverTracingObservationHandler`, compilados desde fuentes) y un tracer de prueba con propagación W3C: la escritura en el outbox es hija de la request y su contexto viaja con el evento (`OutboxTraceTest`); al publicar, aunque el lote del relay tenga su propia observación, `outbox dispatch` continúa la escritura, `publish` es su hijo, la extensión `outboxtraceparent` no llega al broker y el `traceparent` publicado es el de `publish` (`OutboxTraceRelayTest`); el relay entrega al publicador lo que guardó el writer (`OutboxTest`). Encadenado de punta a punta: request → outbox write → outbox dispatch → publish → handler del consumidor, una sola traza; solo el lote del relay queda fuera |
| Gates de publicación | `.github/scripts/check-publish-gates.py` pasa con `ci.yaml` y falla si se quita un gate de `needs` |
| Wrapper de Maven | `.github/scripts/test-mvnw.sh`: sin checksum fijado en modo estricto falla, checksum publicado OK, checksum fijado OK, distribución alterada rechazada y no descomprimida; con Maven en caché el modo estricto también exige el checksum fijado |
| Formato | google-java-format sobre todo el código; ninguna línea supera 120 columnas fuera de imports/URLs |

## Omitido localmente (corre en CI)

- `axiz-architecture-tests` y `axiz-test-spring-boot-starter`: necesitan `archunit-junit5`,
  Testcontainers y WireMock en tiempo de ejecución.
- Partes que dependen de artefactos no disponibles offline: proveedor OpenFeature (2 archivos de
  `axiz-featureflags-spring-boot-autoconfigure`), integración de tenancy con Hibernate (2 archivos),
  `aws-msk-iam-auth`, BouncyCastle para argon2/bcrypt.
- Checkstyle, Error Prone + NullAway, convergencia de dependencias, SpotBugs y PIT: corren con
  `./mvnw -Pquality verify` (CI) y nunca se ejecutaron en este entorno, que no tiene acceso a Maven
  corre sobre JDK 25), así que la primera vez conviene ejecutar `./mvnw spotless:apply`.
- Pruebas de contexto de Spring, de integración con contenedores y compilación nativa. Las pruebas
  de los filtros de idempotencia (servlet y reactivo) y del pipeline CQRS compilan aquí y se
  ejecutan en CI.
- `axiz-store-integration-tests`: todas sus pruebas necesitan Docker; CI las ejecuta con
  `-Daxiz.it.requireDocker=true`, así una máquina sin Docker falla en vez de omitirlas.
- El checksum oficial de la distribución de Maven no pudo fijarse desde este entorno (sin acceso a
  Maven Central): el wrapper lo verifica contra el `.sha512` publicado y el release exige fijarlo
  con `./mvnw --pin-checksum`.

## Cómo reproducir en CI o en una máquina con Nexus

```bash
./mvnw -Pquality verify                 # framework completo con todos los gates
.github/scripts/samples.sh              # instala y genera un servicio por arquetipo en target/samples
for d in target/samples/*/; do (cd "$d" && ./mvnw verify); done
```
