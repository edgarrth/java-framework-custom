# Calidad

Toda regla que una herramienta puede verificar se verifica en CI y rompe el build; las demás entran
en la checklist de revisión del PR. El build local por defecto (`./mvnw verify`) es rápido:
compila, aplica las reglas base del enforcer y corre las pruebas. El perfil
`quality` (`./mvnw -Pquality verify`) agrega los análisis pesados y es el que corre CI en cada PR
y antes de publicar; cualquiera puede correrlo en local antes de abrir el PR. Las configuraciones viven en `axiz-code-standards` y los parents
las referencian: un servicio no copia archivos de configuración.

| Área | Práctica | Umbral o regla |
|---|---|---|
| Dependencias | maven-enforcer: `banDuplicatePomDependencyVersions`, versiones de plugins fijadas, Java 25, sin SNAPSHOT en releases (siempre); `dependencyConvergence`, `requireUpperBoundDeps` y `banDuplicateClasses` (`-Pquality`); `check-dependency-governance.py` en CI | falla el build |
| Capas | `bannedDependencies` por parent (la capa 1 prohíbe Spring, Quarkus, AWS SDK v1, Hazelcast, GCP, Vault, agentes de OTel, Retrofit, Feign y librerías de test en compile) | falla el build |
| Arquitectura | ArchUnit: reglas del estilo y [reglas generales](arquitectura.md) | falla el build |
| Sin APIs deprecadas | ArchUnit (`NO_DEPRECATED_API`, en cada `ArchitectureTest`): ninguna clase usa una API marcada `@Deprecated`; el compilador corre con `-Xlint:all` y el código del framework, del demo y de los proyectos generados compila sin advertencias. Los modelos que genera openapi-generator usan `@Nullable` de JSpecify | falla el build |
| Nombres de bean únicos | ArchUnit (`UNIQUE_BEAN_NAMES`, en cada `ArchitectureTest`): una clase `@Configuration` o `@Component` no puede llamarse como un método `@Bean` u otro componente; el error no lo ve el compilador ni una prueba unitaria y el servicio no arranca | falla el build |
| Formato | Spotless con google-java-format (Java); `./mvnw spotless:apply` formatea (`-Pquality`) | falla con archivos sin formatear |
| Convenciones | Checkstyle: nombres, imports, Javadoc de la API pública, APIs prohibidas (`-Pquality`) | severidad error |
| Errores y nulos | Error Prone + NullAway sobre JSpecify, `@NullMarked` en cada paquete (`-Pquality`) | falla el build |
| Bytecode y seguridad | SpotBugs + FindSecBugs | sin advertencias de prioridad alta |
| Modernización | OpenRewrite (análisis estático) en modo informe en cada PR | informativo |
| Cobertura | JaCoCo por módulo, sobre el código escrito: los modelos que genera openapi-generator llevan `@Generated` de axiz (retención en el bytecode) y JaCoCo los deja fuera, como a todo lo marcado así. Ver [Cobertura de fuentes y modelos](#cobertura-de-fuentes-y-modelos) | al menos 80 % de líneas y 70 % de ramas |
| Calidad de pruebas | PIT en la capa 1 | al menos 81 % de mutantes eliminados |
| Integración | Failsafe + Testcontainers + LocalStack; BlockHound en reactivo | obligatorio por starter |
| Nativo | compilación y pruebas nativas del demo y de un servicio por arquetipo | nocturno en verde |
| SAST | CodeQL (`security-extended`, sobre el código que se distribuye: excluye `src/test`) y las reglas Semgrep de axiz | sin alertas abiertas |
| Vulnerabilidades | Trivy sobre dependencias (SBOM), filesystem e imagen | bloquea siempre MEDIUM/HIGH/CRITICAL, incluidas dependencias de desarrollo |
| SBOM y procedencia | CycloneDX por artefacto; procedencia SLSA y SBOM como atestaciones de GitHub | en cada imagen y artefacto publicado |
| Secretos | secret scanning de GitHub con push protection y gitleaks en pre-commit (`.pre-commit-config.yaml`) | cero detecciones |
| Licencias | dependency review con licencias denegadas (AGPL, GPL-3.0, SSPL) | falla el PR |
| Otros linters | Spectral (OpenAPI, AsyncAPI), actionlint y zizmor (workflows), helm lint y kubeconform, markdownlint (docs) | falla el PR |
| Documentación | README obligatorio por módulo (enforcer), metadata de propiedades y ejemplos de uso | falla el build si falta el README |

## Librerías de terceros

Cada librería de terceros tiene **una sola versión, definida en un solo lugar**:
`platform/axiz-dependencies/pom.xml` o los BOM que importa (Spring Boot, Spring Cloud, Spring Cloud
AWS, AWS SDK, Resilience4j, ShedLock). Los módulos, los servicios y el demo declaran las
dependencias sin versión. Que varios módulos declaren la misma librería, por ejemplo `slf4j-api`,
no la duplica: Maven deja una sola copia por artefacto en el classpath final, con la versión del
BOM.

Tres controles lo sostienen:

- `check-dependency-governance.py` (CI) falla si un módulo fija la versión de una librería de
  terceros, si declara la misma dependencia dos veces o si una propiedad de versión aparece en
  dos POM con valores distintos. También falla si un starter de estilo trae un pool de conexiones
  (JDBC, JPA o R2DBC): la persistencia la elige el servicio, y un pool sin base de datos impide
  arrancar a un servicio sobre DynamoDB. Batch es la excepción, porque Spring Batch guarda su
  repositorio de jobs en Aurora. Se corre sobre el framework en su CI y acepta varias raíces:
  `check-dependency-governance.py axiz-framework axiz-tools axiz-demo` aplica la misma regla a las
  herramientas y al demo, sin excepciones (las versiones que las recetas de OpenRewrite alinean
  también viven en `axiz-dependencies`).
- `dependencyConvergence` y `requireUpperBoundDeps` (`-Pquality`) fallan si dos caminos del árbol
  piden versiones distintas de la misma librería.
- `banDuplicateClasses` (`-Pquality`) falla si dos jars distintos traen la misma clase: es el
  conflicto que no se ve en los nombres de los artefactos (por ejemplo, una librería que embebe
  otra).

Cuando un aviso de seguridad afecta a una dependencia transitiva, la versión corregida se fija en
`axiz-dependencies` (una entrada en `dependencyManagement` o el BOM de la librería, antes del de
Spring Boot) con el aviso en un comentario, y se retira cuando el BOM que la trae ya la incluye.
Así están fijadas hoy las líneas de Jackson 2 y 3, `lz4-java` (de `kafka-clients`) y el cliente
SCRAM (de `r2dbc-postgresql`). Tampoco entran versiones previas: Lettuce trae `redis-authx-core`
0.1.1-beta2, que solo usa su proveedor de tokens de Entra ID, y se excluye en `axiz-dependencies`.
Las excepciones a `banDuplicateClasses` se declaran por artefacto
y por clase, con su justificación, nunca para un jar entero: hoy solo los `package-info` que
repiten los parsers de OpenRewrite en `axiz-rewrite-recipes` y las anotaciones JSpecify que
embebe `wiremock-standalone`.

Además, cada capacidad usa una sola librería para cada función:

| Función | Librería |
|---|---|
| JSON | Jackson 3 (`tools.jackson`) |
| Logging | SLF4J + Logback |
| HTTP saliente | `RestClient` / `WebClient` de Spring en los starters; el cliente HTTP del JDK en la capa 1 (sin dependencias) y en la CLI |
| HTTP hacia AWS | el cliente Apache del SDK (sin el cliente asíncrono de Netty, que se excluye en el BOM) |
| Redis | Lettuce |
| JWT/JOSE | Nimbus, la misma versión que usa Spring Security |
| Mapeo de objetos | MapStruct |
| Pruebas | JUnit 5, AssertJ, Mockito y Testcontainers |

No se usa Lombok: los modelos son `record` de Java y el resto del código es explícito.

## Cobertura de fuentes y modelos

El umbral de JaCoCo mide el código que escribe el equipo, que es el que sus pruebas deben ejercitar.
Los modelos que openapi-generator produce desde el contrato (getters, setters, `equals`,
`hashCode`, `toString`) no son código del servicio: su forma la fija el contrato, que validan las
pruebas de ejemplos y el lint, y su comportamiento lo prueba el generador. `axiz-parent-service`
configura el generador para anotarlos con `pe.axiz.core.commons.Generated`, de retención `CLASS`;
JaCoCo excluye por diseño toda clase con una anotación visible en el bytecode cuyo nombre contiene
`Generated` (la `jakarta.annotation.Generated` que el generador también escribe es de retención
`SOURCE` y no llega a las clases). No es una exclusión por paquete ni por módulo: el umbral y las
reglas no cambian, y la anotación no se pone a mano sobre código escrito.

El reporte agregado (`quality/axiz-coverage-report`, en `target/site/jacoco-aggregate`) reúne a
todos los módulos con código del framework. Cada uno entra con alcance `provided`, porque
`report-aggregate` de JaCoCo solo toma clases de dependencias `compile`, `runtime` o `provided`: con
alcance `test` el reporte salía sin clases. Con `-Pquality` el build falla si el agregado sale
vacío. El reporte se genera en los builds locales completos; en CI el framework se verifica por
capas en jobs paralelos y el umbral de cobertura se exige en cada módulo.

PIT corre con `axiz.pit.threads` hilos por módulo (4 por defecto, los vCPU de un runner hospedado).

Las ramas del código escrito se cubren con pruebas de comportamiento: adaptadores REST sobre los
casos de uso reales, adaptadores de persistencia sobre el cliente real de DynamoDB o Spring Data
con un almacenamiento en memoria, y cada rama de error que el código traduce.

## Avisos de prioridad media

`EI_EXPOSE_REP`, `EI_EXPOSE_REP2` y `CT_CONSTRUCTOR_THROW` están activos. SpotBugs corre con
`threshold` y `failThreshold` en `Medium`, así que el perfil `quality` falla también por estos
avisos de prioridad media. Las colecciones de configuración se copian al construirlas;
los resultados del generador también protegen los arrays y los árboles de propiedades anidados.
Las pruebas verifican que modificar los argumentos o los valores devueltos no altere el estado.

El filtro compartido solo exceptúa clases y miembros concretos: colaboradores inyectados cuyo
ciclo de vida debe compartirse, objetos que ya son inmutables y constructores que validan sus
argumentos. Estos últimos no publican `this` ni usan finalizadores; se conserva su compatibilidad
con extensiones y proxies de la aplicación. Esta justificación presupone código de aplicación
confiable en el mismo proceso, no subclases hostiles cargadas por terceros. Cada excepción lleva
su motivo en el filtro y las de constructores indican sus tipos de parámetros exactos.

Un consumidor puede añadir `spotbugs-exclude.xml` mediante `excludeFilterFiles` del plugin,
conservando el filtro compartido. El gate valida también esas excepciones locales y exige clase
y miembro concretos. Los modelos que OpenAPI genera durante el build se reconocen por
`pe.axiz.core.commons.Generated` (retención `CLASS`), no por excluir todo un paquete de modelos.
Esta anotación nunca debe colocarse en código mantenido a mano.

SpotBugs y FindSecBugs informan desde prioridad media y el build falla con prioridad alta. Un aviso
medio que señala un defecto se corrige en el código (así se corrigieron lecturas repetidas de
valores que pueden ser `null` y una marca leída fuera de su candado). Los que quedan se revisaron
uno por uno y son intencionales; no hay filtro de exclusión para ellos, así que siguen visibles en
cada corrida:

| Aviso | Dónde | Por qué se mantiene |
|---|---|---|
| `PREDICTABLE_RANDOM` | `Backoff` | es el jitter entre reintentos, no un secreto |
| `SQL_INJECTION_JDBC` | `JdbcInboxStore`, `JdbcOutboxStore`, `JdbcSagaStateStore`, `JdbcSequenceStore` | solo el nombre de tabla se concatena, validado como identificador por `SqlStates.identifier`; los valores van como parámetros |
| `WEAK_MESSAGE_DIGEST_SHA1` | `WsSecurity` | el `PasswordDigest` de WS-Security lo exige; solo con `LEGACY_SHA1_DIGEST` explícito, sobre `https://` y con WARN |
| `HARD_CODE_PASSWORD` | `AxizR2dbcEnvironmentPostProcessor` | es la referencia `${spring.r2dbc.password:}`, no una contraseña |
| `XSS_SERVLET` | `ProblemAuthenticationEntryPoint`, `RateLimitFilter`, `SecurityProblems`, `RequestMetadataFilter` | escriben `application/problem+json` serializado (o textos fijos del catálogo de errores), no HTML |
| `COMMAND_INJECTION` | `ProcessRunner` de la CLI | la CLI ejecuta `mvn` y `docker` por diseño, con argumentos que arma ella |

## Herramientas y su trabajo

Cada herramienta hace un solo trabajo y no se pisan:

- **Formato**: Spotless con google-java-format. Checkstyle no revisa espacios, sangrías ni llaves.
- **Convenciones**: Checkstyle revisa lo que un formateador no cubre: nombres
  (`^pe\.axiz(\.[a-z][a-z0-9]*)*$` para paquetes del framework), imports (`AvoidStarImport`,
  `IllegalImport` de APIs internas del JDK, JUnit 4 y `javax.*` donde corresponde Jakarta),
  `System.out` y `printStackTrace`, `HideUtilityClassConstructor`, Javadoc de tipos públicos.
- **Errores en compilación**: Error Prone + NullAway, con las mismas anotaciones JSpecify que usa
  Spring 7, en el perfil `quality`.
- **Bytecode y seguridad**: SpotBugs con FindSecBugs.
- PMD queda fuera para no duplicar reglas.

```bash
./mvnw verify                 # build local: enforcer, pruebas, ArchUnit
./mvnw -Pquality verify       # gate de CI: + formato (Spotless), Checkstyle, Error Prone + NullAway, convergencia,
                              # SpotBugs/FindSecBugs, JaCoCo, PIT
./mvnw spotless:apply         # formatea
```

## Versionado y soporte

SemVer con un tren alineado a la minor de Spring Boot; se soportan la versión actual y la minor
anterior. Toda API obsoleta se marca `@Deprecated(forRemoval = true)` con al menos una minor de
aviso, y cada release incluye recetas OpenRewrite para migrar los servicios. Conventional Commits y
changelog automático.

## Reproducibilidad desde Git

La configuración `.mvn/jvm.config`, `.mvn/maven.config` y `.mvn/wrapper/maven-wrapper.properties`
es código de build y debe versionarse. Los wrappers y scripts de entrada conservan el modo Git
`100755`; `chmod` local no basta si `core.filemode=false`. El módulo `coverage` del demo también
contiene fuentes y pruebas, aunque el nombre coincida con exclusiones globales de herramientas.
Los `.gitignore` de los repositorios y de los proyectos generados preservan esos archivos y siguen
excluyendo targets, POM aplanados y secretos.

El gate `check-build-inputs.py` del framework comprueba el índice de Git, los permisos y los POM de
los módulos. Sus regresiones recrean un repositorio donde los archivos existen localmente pero no
están versionados. La validación de entrega debe exportar el árbol que se va a publicar a un
directorio vacío y ejecutar el wrapper y el perfil de calidad allí, sin copiar configuración del
workspace. Los artefactos del repositorio local de Maven son una caché de dependencias; no sustituyen
las fuentes ni los archivos de configuración del proyecto exportado.
