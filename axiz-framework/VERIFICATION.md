# Verificación de esta entrega

## Qué se verificó y dónde (review37)

Las plantillas de `library`, `service` y `starter` explican qué guardan `version.txt` y
`.release-please-manifest.json` (`0.0.0`: nada liberado todavía) frente al pom (`0.1.0-SNAPSHOT`).
Sin cambios de código en el framework; `axiz-codegen` pasa sus 81 pruebas con las plantillas

## Qué se verificó y dónde (review34)

La corrida de review33 pasó el gate completo del framework (`-Pquality`, 33 min) y el de las
herramientas. review34 corrige dos avisos de SpotBugs de prioridad media que esa corrida mostró:
`LockTemplate.Lease` lee bajo el mismo candado que escribe la marca de interrupción, y
`AudienceValidator` lee una sola vez el emisor y la audiencia del token. Verificado aquí: NullAway
y Error Prone sobre `axiz-core-lock`; 139 módulos compilan y 601 pruebas pasan con el arnés local.

## Qué se verificó y dónde (review33)

La corrida de review32 falló en `requireUpperBoundDeps` por SLF4J (2.0.19 fijada, 2.0.20 pedida
por Spring Boot 4.1.2). review33 quita la fijación y excluye `redis-authx-core` (beta) de Lettuce;
solo cambia `axiz-dependencies`. El resultado del árbol de dependencias se confirma en tu corrida:

## Qué se verificó y dónde (review32)

review32 responde a la auditoría del 24 de octubre de 2026 (R1 a R7). El detalle está en

| Nivel | Dónde | Resultado |
|---|---|---|
| Compilación de main y pruebas | aquí, `javac` sin Maven | 142 módulos OK; los servicios del demo se compilan aparte con modelos sustitutos (0 fallas) |
| Pruebas unitarias | aquí, JUnit | 640 OK, 0 fallidas |
| Starters cambiados | aquí, arnés de Spring | caché 17/17 (alcance por tenant), logging y seguridad (perfil del access token, 8/8) |
| Pruebas nuevas de la capa 1 | aquí | `LockLossTest` 6/6 (15 corridas), `EnvelopeKeyCacheTest`, `SensitiveTest` 26/26 y 30.074 casos de JSON contrastados con otro parser |
| Error Prone 2.49.0 + NullAway | aquí, módulos cambiados | 0 errores, 0 avisos |
| Checkstyle 14.2.0, google-java-format 1.30.0 | aquí, framework, herramientas y demo | sin hallazgos ni cambios pendientes |
| Documentación | aquí, `mkdocs build --strict` | sin avisos |
| Versiones nuevas del BOM (Spring Boot 4.1.2, Jackson, AWS SDK, lz4, SCRAM) | solo tu máquina o CI | aquí no hay Maven Central |
| PIT, `-Pquality` completo, `*IT` con Docker | solo CI o tu máquina | no se ejecutaron aquí |

## Qué se verificó y dónde (review31)

La corrida de `-Pquality` sobre review30 falló solo en Spotless de una prueba de
`axiz-soap-spring-boot-autoconfigure`. Verificado aquí: google-java-format 1.30.0 con Java 25.0.3
y sin reacomodar textos largos, igual que Spotless, sobre todo el código de framework,
herramientas y demo: un solo archivo cambia, el que señaló tu corrida. El detalle está en

## Qué se verificó y dónde (review30)

review30 responde a la corrida de `mvn -Pquality verify` sobre review29 del 24 de octubre de 2026

| Nivel | Dónde | Resultado |
|---|---|---|
| Compilación de main y pruebas | aquí, `javac` sin Maven | 139 módulos OK, 2 omitidos (los mismos de siempre) |
| Pruebas unitarias | aquí, JUnit | 586 OK, 0 fallidas (`axiz-core-soap`: 15, seis nuevas) |
| Starters cambiados | aquí, arnés de Spring | BFF 4/4, auditoría 11/12 (la de SQS, por el stub del arnés; ver review28) |
| Error Prone 2.49.0 + NullAway 0.14.1, leyendo los demás módulos desde sus clases compiladas como Maven | aquí, 106 módulos con código de producción | 0 errores y 0 avisos; `axiz-featureflags` y `axiz-test-spring-boot-starter` no se pueden compilar aquí por librerías que faltan (pasaron en tu corrida) |
| SpotBugs 4.10.4 + Find Security Bugs 1.14.0 | aquí, módulos cambiados | sin avisos nuevos |
| Checkstyle 14.2.0, google-java-format 1.30.0 | aquí | sin hallazgos ni cambios pendientes |
| PIT, `-Pquality` completo, `*IT` con Docker | solo CI o tu máquina | no se ejecutaron aquí |

Cobertura de `axiz-core-soap`: 0,93 / 0,93 (líneas / ramas).

## Qué se verificó y dónde (review29)

review29 responde a la corrida de `mvn -Pquality verify` sobre review28 del 24 de octubre de 2026

| Nivel | Dónde | Resultado |
|---|---|---|
| Compilación de main y pruebas | aquí, `javac` sin Maven | 139 módulos OK, 2 omitidos (los mismos de review28) |
| Pruebas unitarias (capa 1, APIs de estilos, codegen) | aquí, JUnit | 580 OK, 0 fallidas |
| Pruebas de los starters y autoconfiguraciones cambiados | aquí, arnés de Spring | errors 12/14 (las dos de siempre, ver review28), idempotency 24/24, r2dbc 21/21, messaging 23/23, cache 11/11, http-client 9/9, storage 5/5, BFF 4/4, EDA 5/5 |
| SpotBugs 4.10.4 + Find Security Bugs 1.14.0, configuración del proyecto | aquí, framework, CLI y demo | 0 de prioridad alta; de prioridad media quedan solo los justificados en el sustento (SQL con nombre de tabla validado, SHA-1 del perfil WS-Security, variación de reintentos, referencia a la contraseña de R2DBC, procesos del CLI) |
| Checkstyle 14.2.0 | aquí | sin hallazgos en el reactor |
| google-java-format 1.30.0 más `formatAnnotations` | aquí | sin cambios pendientes |
| Error Prone 2.49.0 + NullAway 0.14.1 en el código de producción cambiado | aquí | 0 errores, 0 avisos |
| PIT, `-Pquality` completo, `*IT` con Docker | solo CI o tu máquina | no se ejecutaron aquí |

Cobertura de los módulos que la corrida encontró bajo el umbral (líneas / ramas): `axiz-core-soap`
0,92 / 0,93 y `axiz-idempotency-spring-boot-autoconfigure` 0,84 / 0,77. De los que no alcanzó a
medir: `axiz-lock-spring-boot-autoconfigure` 0,85 / 1,00 y `axiz-soap-spring-boot-autoconfigure`
0,98 / 0,89.

Defecto real encontrado: `JsonTextMasker` lanzaba `StackOverflowError` con un valor de texto de
unos 5.000 caracteres en el cuerpo que se registraba; ahora lee el cuerpo en una sola pasada.

## Qué se verificó y dónde (review28)

review28 responde a la validación del 23 de octubre de 2026 sobre review27, hecha con Maven y
Docker reales: 845/845 pruebas del framework, pero el perfil `quality` terminó en `BUILD FAILURE`
(nueve módulos bajo JaCoCo, tres archivos de Spotless, tres violaciones de Enforcer) y tres pruebas
nuevas de límites de caché fallaban. Este entorno sigue sin Maven Central; lo que sigue separa lo
ejecutado aquí de lo que solo CI (o tu máquina) puede ejecutar.

| Nivel | Dónde | Resultado |
|---|---|---|
| Compilación de main y pruebas | aquí, `javac` sin Maven | 139 módulos OK, 2 omitidos (`axiz-architecture-tests` y `axiz-test-spring-boot-starter`: ArchUnit JUnit, Testcontainers y WireMock) |
| Pruebas unitarias (capa 1, APIs de estilos, codegen) | aquí, JUnit | 557 OK, 0 fallidas |
| Pruebas de límites de caché de la validación (`CorrectionEdgeCasesTest`, sin cambios en sus aserciones) | aquí, JUnit | 3/3 OK (antes 0/3) |
| Checkstyle 14.2.0 | aquí | sin hallazgos en el reactor |
| google-java-format 1.30.0 más el paso `formatAnnotations` de Spotless | aquí, todo el reactor | sin cambios pendientes |
| Error Prone 2.49.0 + NullAway 0.14.1 en el código de producción cambiado | aquí | 0 errores, 0 avisos |
| Enforcer (`banDuplicateClasses`, `requireUpperBoundDeps`) | revisión de POM | JSpecify de WireMock declarado, AOP Alliance excluido, `slf4j-api` 2.0.19; Enforcer corre en CI |
| Cobertura | aquí, JaCoCo 0.8.14 | ver la tabla siguiente |
| `-Pquality` completo, PIT, SpotBugs, `*IT` con Docker | solo CI o tu máquina | no se ejecutaron aquí |

Cobertura medida aquí (líneas / ramas). El arnés compila Spring desde fuentes; cuando le falta una
clase que Spring carga por nombre, la prueba afectada falla aquí aunque pase con Maven, y la cifra
de aquí es un mínimo. El detalle por módulo y las pruebas que solo corren con Maven están en

| Módulo | Validación del 23 de octubre | Ahora |
|---|---|---|
| `axiz-core-sequence-dynamodb` | 0,95 / 0,50 | 1,00 / 1,00 |
| `axiz-audit-spring-boot-autoconfigure` | 0,50 / 0,42 | 0,94 / 0,88 |
| `axiz-errors-spring-boot-autoconfigure` | 0,83 / 0,67 | 0,89 / 0,84 |
| `axiz-http-client-spring-boot-autoconfigure` | 0,59 / 0,45 | 0,84 / 0,81 |
| `axiz-logging-spring-boot-autoconfigure` | 0,57 / 0,36 | 1,00 / 0,79 |
| `axiz-messaging-spring-boot-autoconfigure` | 0,44 / 0,26 | 0,88 / 0,72 |
| `axiz-metrics-spring-boot-autoconfigure` | 0,63 / 0,38 | 0,98 / 0,88 |
| `axiz-observability-spring-boot-autoconfigure` | 0,58 / 0,50 | 1,00 / 0,81 |
| `axiz-r2dbc-spring-boot-autoconfigure` | 0,53 / 0,52 | 0,96 / 0,87 |
| Starters de batch, BFF, CQRS, EDA, hexagonal y layered | no medidos (omitidos) | entre 0,89 y 1,00 / entre 0,80 y 1,00 |
| `axiz-inbox`, `axiz-outbox`, `axiz-scheduling`, `axiz-sequence`, `axiz-saga` (autoconfigure) | no medidos (omitidos) | entre 0,89 y 1,00 / entre 0,75 y 1,00 |

Corrección de comportamiento encontrada al escribir las pruebas: Apache HttpClient reenviaba una
vez cualquier request respondido con 429 o 503, `POST` incluido; sus reintentos automáticos quedan
apagados y solo reintenta el interceptor de axiz (llamadas idempotentes).

## Qué se verificó y dónde (review27)

review27 aplica tres auditorías independientes sobre review26 (informe técnico con 9 hallazgos,
auditoría del 17 de octubre de 2026 con R01 a R09 e informe con 22 hallazgos `AXZ-*`). La
auditoría del 17 de octubre ejecutó Maven real: 683/683 pruebas del framework, pero el perfil
`quality` fallaba (Checkstyle, Spotless, 18 avisos de NullAway, Enforcer en 2 módulos y cobertura
bajo el umbral en 14 módulos). Este entorno sigue sin Maven Central; lo que sigue separa lo ejecutado
aquí de lo que solo CI puede ejecutar.

| Nivel | Dónde | Resultado |
|---|---|---|
| Compilación de main y pruebas | aquí, `javac` sin Maven | 139 módulos OK, 2 omitidos (`axiz-architecture-tests` y `axiz-test-spring-boot-starter`: Testcontainers y WireMock) |
| Pruebas unitarias (capa 1, APIs de estilos, codegen, ArchUnit) | aquí, JUnit | 551 OK, 0 fallidas (incluye cifrado concurrente con hilos virtuales y suma de 10 000 centavos sin deriva) |
| Checkstyle 14.2.0 con la configuración de `axiz-code-standards` | aquí | sin hallazgos en los cinco repositorios y en los 29 proyectos generados |
| google-java-format 1.30.0 (la versión de Spotless) | aquí | sin cambios pendientes |
| Error Prone 2.49.0 + NullAway 0.14.1 con las opciones del POM | aquí | 0 errores y 0 avisos; los avisos se corrigieron en el código, sin supresiones generales. No analizables aquí: `featureflags` (OpenFeature usa Lombok), `r2dbc` (sobrecargas de Flyway y RDS), `tenancy` (Hibernate) y el starter de pruebas |
| Convergencia de dependencias | revisión de POM | `slf4j-api` de ArchUnit excluido y declarado; Bouncy Castle alineado en 1.85; Enforcer corre en CI |
| Cobertura de las auto-configuraciones que la auditoría marcó bajo el umbral (líneas 0,80, ramas 0,70) | aquí, JaCoCo 0.8.14 con pruebas de contexto de Spring (`ApplicationContextRunner`) | ver la tabla siguiente |
| Charts (9 charts × dev/qa/prod/kind) | aquí | kubeconform estricto (Kubernetes 1.33): 276 recursos válidos, 0 inválidos (20 omitidos: CRDs como `ExternalSecret`); kube-linter 0.7.6 sin hallazgos |
| Workflows | aquí | actionlint 1.7.12 sin hallazgos |
| `-Pquality` completo, PIT, SpotBugs, `*IT` con Docker, `api-compat` | solo CI | no se ejecutaron |

Cobertura medida aquí (líneas / ramas). El arnés compila Spring desde fuentes; cuando falta alguna
clase que Spring carga por nombre, la prueba afectada falla aquí aunque pase con Maven, y se indica:

| Módulo | Antes (auditoría) | Ahora |
|---|---|---|
| `axiz-storage-spring-boot-autoconfigure` | 0,20 / 0,00 | 1,00 / 0,90 |
| `axiz-tenancy-spring-boot-autoconfigure` | 0,40 / 0,31 | 1,00 / 1,00 |
| `axiz-jpa-spring-boot-autoconfigure` | 0,77 / 0,66 | 1,00 / 1,00 |
| `axiz-foundation-spring-boot-autoconfigure` | 0,54 / 0,50 | 0,98 / 0,81 |
| `axiz-soap-spring-boot-autoconfigure` | sobre umbral / 0,50 | 0,98 / 0,89 |
| `axiz-i18n-spring-boot-autoconfigure` | 0,64 / sin ramas | 0,88 / sin ramas (falta aquí el resolvedor reactivo, que cubre la prueba nueva en CI) |
| `axiz-lock-spring-boot-autoconfigure` | 0,55 / sobre umbral | 0,85 / 1,00 |
| `axiz-crypto-spring-boot-autoconfigure` | 0,59 / 0,28 | 0,83 / 0,79 (la prueba de KMS necesita el SDK real, CI) |
| `axiz-dynamodb-spring-boot-autoconfigure` | sin pruebas | 1,00 / 1,00 |
| `axiz-web-spring-boot-autoconfigure` | 0,56 / 0,58 | 0,95 / 0,80 |
| `axiz-errors-spring-boot-autoconfigure` | 0,41 / 0,42 | 0,63 / 0,52 aquí, con 5 de 6 pruebas: la prueba MVC de extremo a extremo (validación, header faltante, cuerpo ilegible, 401, 403, 409, 404, 500) cubre `SpringExceptionMappers` y no arranca aquí porque Spring MVC necesita clases que el arnés no compila; corre en CI |
| `axiz-idempotency-spring-boot-autoconfigure` | 0,49 / 0,47 | 0,59 / 0,50 aquí, con 17 de 21 pruebas: las 4 del filtro reactivo cubren `IdempotencyWebFilter` (97 líneas) y no arrancan WebFlux en el arnés; corren en CI |
| `axiz-secrets-spring-boot-autoconfigure` | 0,56 / 0,50 | 0,66 / 0,88 aquí: las fuentes de Secrets Manager y Parameter Store necesitan los clientes reales del SDK, que solo existen en CI; sus pruebas nuevas están escritas |
| `axiz-featureflags-spring-boot-autoconfigure` | 0,22 / 0,21 | no medible aquí (Lombok); pruebas nuevas del proveedor de OpenFeature |
| `axiz-r2dbc-spring-boot-autoconfigure` | 0,45 / 0,48 | no medible aquí (pool de R2DBC y Flyway); prueba nueva del acceso de Flyway con IAM |

Correcciones de comportamiento encontradas al escribir las pruebas (cada una con su prueba):
una bandera de OpenFeature no configurada devolvía `false` en vez del valor por defecto de quien
consulta (ahora `FLAG_NOT_FOUND`); un perfil cuyo nombre contenía `test` o `local` permitía
`ddl-auto=update`; un cliente SOAP mal configurado fallaba con `NullPointerException` en vez de
nombrar la propiedad; Parameter Store sin ruta fallaba igual; Argon2id caía a PBKDF2 sin avisar
(ahora deja un WARN).

## Qué se verificó y dónde (review26)

La última ejecución con Maven real es la auditoría de código del 16 de octubre de 2026 sobre
review24 (Fedora en WSL2, JDK 25.0.3, Docker y Testcontainers reales): 614 pruebas del framework,
598 aprobadas, 5 fallos y 11 errores; el perfil `quality` no inicializaba Checkstyle. review26
corrige esos hallazgos. Esta entrega se construyó en un entorno sin Maven Central, así que lo que
sigue separa lo ejecutado aquí de lo que solo CI (o tu máquina) puede ejecutar. Desde review26 cada
ejecución de CI publica en el resumen del job el commit, el JDK, el comando y las pruebas por módulo
(`axiz-workflows/actions/test-report`).

| Nivel | Dónde | Resultado |
|---|---|---|
| Compilación de main y pruebas | aquí, `javac` sin Maven | 139 módulos OK, 2 omitidos (`axiz-architecture-tests` y `axiz-test-spring-boot-starter`: necesitan `archunit-junit5`, Testcontainers y WireMock) |
| Pruebas unitarias (capa 1, APIs de estilos, codegen, ArchUnit) | aquí, JUnit | 510 OK, 0 fallidas |
| Cobertura JaCoCo 0.8.14 (agente) con los umbrales del perfil `quality` (líneas 0,80, ramas 0,70) | aquí, en los módulos cuyas pruebas corren aquí | 60 módulos medidos, todos sobre los umbrales (el menor: 0,81 de líneas y 0,70 de ramas); antes de review26 quedaban 15 por debajo, entre ellos 3 de los 4 que reportó la auditoría (el cuarto, `cache-redis`, no corre aquí: sus 4 ramas quedan cubiertas por la prueba nueva de paginación del SCAN) |
| Pruebas de contexto de Spring de las auto-configuraciones (`ApplicationContextRunner`) | solo CI | compilan; no se ejecutaron aquí y su cobertura no se midió |
| Checkstyle 14.2 | solo CI | la configuración se validó contra las fuentes de Checkstyle 14.2.0 (cada módulo y propiedad existe); no se ejecutó |
| Error Prone + NullAway | solo CI | los 21 avisos de la auditoría se corrigieron en el código, sin suprimirlos; no se ejecutó |
| Convergencia de dependencias y clases duplicadas | solo CI | JSpecify 1.0.1 y exclusión de `aopalliance`; no se ejecutó |
| SpotBugs, PIT, Spotless | solo CI | no se ejecutaron |
| Integración con contenedores (`*IT`, store contracts reales) | CI o una máquina con Docker | no se ejecutaron |
| Proyectos generados (29 combinaciones) | aquí compilan main y pruebas; CI nocturno (`samples-matrix.yaml`) los construye con Maven sin configuración de AWS | aquí: los 29 compilan main y pruebas con las plantillas de review26 |

Cómo se corrigió cada hallazgo de la auditoría y qué prueba lo reproduce:

| Hallazgo | Corrección | Prueba |
|---|---|---|
| H01 Checkstyle 14.2 sin `JavadocStyle` | `SummaryJavadoc` sin exigir punto final; 3 Javadoc sin descripción completados | configuración validada contra las fuentes 14.2.0 |
| H02 NullAway, JSpecify, cobertura | código sin nulos implícitos (saga con resultados tipados, codegen, ids, tenancy, cifrado); JSpecify 1.0.1; `aopalliance` excluido; pruebas nuevas en `cache-redis`, `logging` (proveedor SLF4J de pruebas que captura cada línea), `batch-api`, `eda-api`, `core-test`, `storage-s3`, `persistence-dynamodb`, `persistence-jpa`, `observability-awssdk`, `messaging-sns`, `inbox`, `inbox-dynamodb`, `outbox-r2dbc`, `store-contracts` (los contratos fallan con stores rotos), `domain` y `archunit-rules` | cobertura medida aquí; los umbrales no cambiaron |
| H03 Failsafe después de `repackage` | `classesDirectory` en `target/classes` | CI (`*IT`) |
| H04 `SsmClient` sin región | Parameter Store sigue a `axiz.config.parameter-store.enabled` | `AxizConfigEnvironmentPostProcessorTest.parameterStoreClientsExistOnlyWhenParameterStoreIsUsed` (CI) |
| H05 `sort` inválido daba 500 | violación del campo `sort` y 400 | `WebTest` (ambos stacks, CI) |
| H06 inglés con idioma por defecto `es-PE` | `messages_en.properties` del framework | `I18nTest` (aquí) |
| H07 fixtures desactualizados | llaves de idempotencia válidas, `FakeR2dbc` con tipos, saga con rechazo e incertidumbre, duraciones con el binder de Boot | aquí lo que no necesita Spring; el resto en CI |
| H10 evidencia | esta sección y `actions/test-report` | |

## Verificaciones de entregas anteriores

El entorno donde se construyó no tenía acceso a Maven Central, así que `./mvnw verify` no pudo
ejecutarse aquí: corre en CI (`.github/workflows/ci.yaml`, que además genera y construye un
servicio por arquetipo). Localmente se verificó con `javac` (JDK 21 con `--release` compatible) y
JUnit Jupiter, usando las fuentes o JARs reales de Spring Boot 4.1, Spring 7, Jackson 3, AWS SDK v2,
Kafka, Lettuce, Reactor, R2DBC (SPI, pool, PostgreSQL), Spring Data R2DBC, Nimbus, ShedLock y
ArchUnit, y stubs de compilación generados solo para
dependencias opcionales de esas librerías (nunca para código de axiz).

### Resultado

| Qué | Resultado |
|---|---|
| Módulos del framework | 139 OK, 2 omitidos (ver abajo); el reporte de cobertura es `pom` |
| Pruebas unitarias ejecutadas | 449 OK, 0 fallidas (capa 1, APIs de los estilos, codegen, reglas ArchUnit), más 9 del store Redis ejecutadas compilando Lettuce desde fuentes |
| Auto-configuraciones y starters de estilos (capa 2/3) | 43 compilan código y pruebas; las pruebas de contexto (`ApplicationContextRunner`) se ejecutan en CI |
| Starters (solo dependencias), plantillas, archetypes | 36 verificados por estructura |
| Proyectos generados (todas las combinaciones válidas: 27 servicios + library + starter, incluidos `business / cqrs / reactive / aurora` y `event-processor / eda / reactive / aurora` sobre R2DBC) | compilan main y test; `ArchitectureTest` OK en los 27 servicios; pruebas unitarias de los generados reactivos con Aurora OK |
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
| Advertencia de `sun.misc.Unsafe` (protobuf) | Leído en las fuentes de protobuf v35.1 y v36.2: en la 4.35.1, `CodedOutputStream` y `Utf8` (el camino de cualquier mensaje) usan `UnsafeUtil`, cuya inicialización llama a `Unsafe::arrayBaseOffset`; en la 4.36.2 solo lo usan las clases del runtime lite y de esquemas (`MessageSchema`, `SchemaUtil`, `GeneratedMessageLite`...), a las que no llega el código generado completo (`GeneratedMessage`, el de `opentelemetry-proto`). `axiz-dependencies` fija `protobuf-java` 4.36.2 |
| Clientes AWS | `AwsClients.configured` ejecutado con un `DynamoDbClient` real del SDK: endpoint compartido, endpoint por servicio y sin endpoint (`AwsClientsTest`). La auditoría por SQS, KMS, Secrets Manager, Parameter Store y los stores en DynamoDB crean su cliente así; antes usaban `XxxClient.create()` y desde local iban a AWS real (el `403` de la auditoría de `core-integration`) |
| Trazas de los clientes HTTP | Ejecutado con Spring Boot 4.1.1 y Spring 7.0.9 compilados desde fuentes: un contexto con las auto-configuraciones de observación, `RestClient`, servicios HTTP y la de axiz, un grupo `@ImportHttpServices` con `axiz.http-client.clients.<grupo>` y un servidor HTTP del JDK. La llamada dentro de una observación llega con `traceparent` de la misma traza y el span del cliente es hijo del actual. El orden real de las auto-configuraciones de `payment-process` y `payments-experience` (reproducido con las reglas de `AutoConfigurationSorter` sobre sus fuentes) deja la observación de `RestClient` después de su registro y de su builder. `payments-experience` no tenía `spring-boot-webclient` (módulo aparte en Boot 4): sus llamadas no se observaban |
| Dependencia que no contesta | Ejecutado con Spring 7.0.9 compilado desde fuentes: `AxizRestClientInterceptor` ante un `SocketTimeoutException` lanza la falla de dependencia 504 (`AXZ-EXT-0504`) con el mensaje `Call to bank got no response (SocketTimeoutException)` y la causa original; `ProblemResponder` la registra en una línea WARN con `cause=SocketTimeoutException: Read timed out`, sin stack trace, y una falla interna sigue en ERROR con el suyo. En el log del demo, cada timeout del core salía como ERROR 503 `failed with status -1` con ~190 líneas. Pruebas en `ErrorsTest` (core, ejecutada), `ErrorsTest` y `HttpClientTest` (autoconfiguraciones, corren en CI) |
| Acceso nativo (JEP 472) | `axiz-parent-service` agrega `Enable-Native-Access: ALL-UNNAMED` al manifiesto del jar (el `repackage` de Spring Boot conserva las entradas del manifiesto), `--enable-native-access=ALL-UNNAMED` a la imagen de Jib y a las pruebas. Es lo que pide el JDK para que Netty cargue su transporte nativo con `System.loadLibrary` (la advertencia de `payments-experience` y `payments-history`); no se puede ejecutar aquí con Java 25, se confirma al arrancar el demo |
| Nombres de bean | `UNIQUE_BEAN_NAMES` ejecutada sobre clases de ejemplo: marca la clase `SettlementJob` con `@Bean settlementJob()` y un componente con nombre explícito repetido, y no marca nombres explícitos, auto-configuraciones ni clases sin conflicto (`AxizArchitectureTest`). Ejecutada con las anotaciones de Spring compiladas desde fuentes sobre los 8 servicios del demo (OK; con la clase `SettlementJob` de la versión anterior la marca) y sobre los 27 generados, donde encontró `XHandler` repetido en `command` y `query` de los artefactos CQRS (corregido: `XCommandHandler`, `XQueryHandler`) |
| Propiedades de Spring Boot | Las propiedades de los YAML, plantillas y values de kind se compararon con las marcadas como eliminadas o deprecadas en los metadatos de Spring Boot 4.1.1 (599): solo aparecía `management.otlp.tracing.endpoint` (Boot 3), reemplazada |
| Gates de publicación | `.github/scripts/check-publish-gates.py` pasa con `ci.yaml` y falla si se quita un gate de `needs` |
| Wrapper de Maven | `.github/scripts/test-mvnw.sh`: sin checksum fijado en modo estricto falla, checksum publicado OK, checksum fijado OK, distribución alterada rechazada y no descomprimida; con Maven en caché el modo estricto también exige el checksum fijado |
| Formato | google-java-format sobre todo el código; ninguna línea supera 120 columnas fuera de imports/URLs |

### Omitido localmente (corre en CI)

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

### Cómo reproducir en CI o en una máquina con Nexus

```bash
./mvnw -Pquality verify                 # framework completo con todos los gates
.github/scripts/samples.sh              # instala y genera un servicio por arquetipo en target/samples
for d in target/samples/*/; do (cd "$d" && ./mvnw verify); done
```
