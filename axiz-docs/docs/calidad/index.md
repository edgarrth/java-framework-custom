# Calidad

Toda regla que una herramienta puede verificar se verifica en CI y rompe el build; las demás entran
en la checklist de revisión del PR. Las configuraciones viven en `axiz-code-standards` y los parents
las referencian: un servicio no copia archivos de configuración.

| Área | Práctica | Umbral o regla |
|---|---|---|
| Dependencias | maven-enforcer: `dependencyConvergence`, `requireUpperBoundDeps`, `banDuplicatePomDependencyVersions`, versiones de plugins fijadas, Java 25, sin SNAPSHOT en releases | falla el build |
| Capas | `bannedDependencies` por parent (la capa 1 prohíbe Spring, Quarkus, AWS SDK v1, Hazelcast, GCP, Vault, agentes de OTel, Retrofit, Feign y librerías de test en compile) | falla el build |
| Arquitectura | ArchUnit: reglas del estilo y [reglas generales](arquitectura.md) | falla el build |
| Formato | Spotless con google-java-format (Java) y sortpom (POM) | falla con archivos sin formatear |
| Convenciones | Checkstyle: nombres, imports, Javadoc de la API pública, APIs prohibidas | severidad error |
| Errores y nulos | Error Prone + NullAway sobre JSpecify (`@NullMarked` en cada paquete) | falla el build |
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

## Herramientas y su trabajo

Cada herramienta hace un solo trabajo y no se pisan:

- **Formato**: Spotless con google-java-format. Checkstyle no revisa espacios, sangrías ni llaves.
- **Convenciones**: Checkstyle revisa lo que un formateador no cubre: nombres
  (`^pe\.axiz(\.[a-z][a-z0-9]*)*$` para paquetes del framework), imports (`AvoidStarImport`,
  `IllegalImport` de APIs internas del JDK, JUnit 4 y `javax.*` donde corresponde Jakarta),
  `System.out` y `printStackTrace`, `HideUtilityClassConstructor`, Javadoc de tipos públicos.
- **Errores en compilación**: Error Prone + NullAway, con las mismas anotaciones JSpecify que usa
  Spring 7. `-Daxiz.errorprone.skip` existe solo como escape local.
- **Bytecode y seguridad**: SpotBugs con FindSecBugs.
- PMD queda fuera para no duplicar reglas.

```bash
./mvnw verify                 # gates rápidos (formato, Checkstyle, Error Prone, pruebas, ArchUnit)
./mvnw -Pquality verify       # + SpotBugs/FindSecBugs, JaCoCo, PIT
./mvnw spotless:apply         # formatea
```

## Versionado y soporte

SemVer con un tren alineado a la minor de Spring Boot; se soportan la versión actual y la minor
anterior. Toda API obsoleta se marca `@Deprecated(forRemoval = true)` con al menos una minor de
aviso, y cada release incluye recetas OpenRewrite para migrar los servicios. Conventional Commits y
changelog automático.
