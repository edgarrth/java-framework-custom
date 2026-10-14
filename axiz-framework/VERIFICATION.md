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
| Módulos del framework | 140 OK, 2 omitidos (ver abajo) |
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
