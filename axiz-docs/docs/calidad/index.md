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
| Formato | Spotless con google-java-format (Java); `./mvnw spotless:apply` formatea (`-Pquality`) | falla con archivos sin formatear |
| Convenciones | Checkstyle: nombres, imports, Javadoc de la API pública, APIs prohibidas (`-Pquality`) | severidad error |
| Errores y nulos | Error Prone + NullAway sobre JSpecify, `@NullMarked` en cada paquete (`-Pquality`) | falla el build |
| Bytecode y seguridad | SpotBugs + FindSecBugs | sin hallazgos de prioridad alta |
| Modernización | OpenRewrite (análisis estático) en modo informe en cada PR | informativo |
| Cobertura | JaCoCo por módulo | ≥ 80 % líneas, ≥ 70 % ramas |
| Calidad de pruebas | PIT en la capa 1 | ≥ 60 % de mutantes eliminados |
| Integración | Failsafe + Testcontainers + LocalStack; BlockHound en reactivo | obligatorio por starter |
| Nativo | compilación y pruebas nativas del demo y de un servicio por arquetipo | nocturno en verde |
| SAST | SonarQube (quality gate) y Semgrep (reglas públicas y de axiz) | sin bloqueantes ni críticos |
| Vulnerabilidades | Trivy sobre dependencias (SBOM), filesystem e imagen | falla con HIGH/CRITICAL sin excepción aprobada |
| SBOM y firma | CycloneDX por artefacto; imágenes firmadas con cosign y SBOM atestado | en cada imagen |
| Secretos | gitleaks en pre-commit (`.pre-commit-config.yaml`) y en cada PR | cero hallazgos |
| Licencias | dependency review con licencias denegadas (AGPL, GPL-3.0, SSPL) | falla el PR |
| Otros linters | Spectral (OpenAPI, AsyncAPI), actionlint, kubeconform y kube-linter, markdownlint (docs), título Conventional Commits | falla el PR |
| Neutralidad de origen | guardia de nombres con la lista de identificadores prohibidos (secreto de la organización) | cero coincidencias |

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
  dos POM con valores distintos.
- `dependencyConvergence` y `requireUpperBoundDeps` (`-Pquality`) fallan si dos caminos del árbol
  piden versiones distintas de la misma librería.
- `banDuplicateClasses` (`-Pquality`) falla si dos jars distintos traen la misma clase: es el
  conflicto que no se ve en los nombres de los artefactos (por ejemplo, una librería que embebe
  otra).

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
