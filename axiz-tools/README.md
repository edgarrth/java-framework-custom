# axiz-tools

Herramientas para desarrolladores de la plataforma axiz. Todas usan el motor de generación del
framework (`pe.axiz:axiz-codegen`), de modo que el generador web, la CLI y CI producen exactamente el
mismo código.

| Módulo | Qué es |
|---|---|
| `axiz-cli` | El comando `axiz` (picocli, binario nativo con GraalVM) |
| `axiz-initializr` | Generador web y API REST detrás del SSO corporativo (también el modo en línea de la CLI) |
| `axiz-rewrite-recipes` | Recetas de OpenRewrite que `axiz upgrade` aplica entre versiones |

## CLI de axiz

```text
axiz new <name> --archetype <a> [--structure] [--stack] [--persistence] [-c <capability>]... --openapi <file>
axiz new -i                                   interactive
axiz add <capability>...                      starter, properties, ConfigMap keys, tables, example
axiz generate <type> <name>                   usecase, command, query, handler, event, event-handler,
                                              entity, repository, controller, dto, mapper,
                                              http-client (--contract), migration, error-code, test
axiz contract sync [--type openapi|asyncapi] [--allow-breaking]         new operations, proposals for existing controllers,
                                              compatibility against the last synced contract
axiz contract lint [--type openapi|asyncapi] [file] | diff [--type openapi|asyncapi] <old> <new>
axiz upgrade [--to <version>] [--dry-run]     parent + manifest + OpenRewrite recipes
axiz doctor                                   tools, versions, coherence, contract, secrets, anti-patterns
axiz local up | down [-v]                     docker compose of the service
axiz catalog [archetypes|capabilities]
```

Sin conexión por defecto (plantillas incluidas en el binario). `--server https://initializr...` genera
mediante el initializr con el token del usuario tomado de `AXIZ_TOKEN` (la CLI nunca lo guarda).

Compilación: `mvn -pl axiz-cli package` (jar JVM `axiz-cli-*-jar-with-dependencies.jar`) o
`mvn -pl axiz-cli -Pnative package` (binario `target/axiz`; necesita GraalVM 25).

## Actualizaciones

`axiz upgrade` prepara las actualizaciones de los proyectos en una copia temporal privada. Actualiza
los parents de Axiz, conserva las versiones de la aplicación y los parents no relacionados, valida con
doctor y aplica los cambios de forma atómica. `--dry-run` conserva un parche sin modificar el proyecto.
Los manifiestos deben usar el esquema 2; cualquier otro esquema o clave desconocida se rechaza.

Los bindings de workflows vigentes pueden renombrarse de forma segura sin descartar jobs
personalizados. Los registros de identidad, las rutas de los charts y las identidades con estado
protegen los renombres futuros de los servicios. Están disponibles las convenciones de Java, Spring y
JSpecify y la reparación del scope de los adaptadores de runtime.

## Initializr de axiz

- Interfaz web en `/` y API bajo `/v1` (contrato en `axiz-initializr/docs/openapi.yaml`):
  `GET /v1/metadata`, `POST /v1/projects` (ZIP), `POST /v1/projects/validate`,
  `POST /v1/contracts/lint`, `POST /v1/projects/github`.
- Seguridad: login OIDC para la interfaz (cookie CSRF de SPA, CSP estricta), JWT bearer para la CLI y
  las automatizaciones, y solo las probes de salud son anónimas. Los repositorios de GitHub se crean
  con la autorización OAuth del propio usuario; los tokens nunca se guardan.
- La generación ocurre en memoria (sin directorios temporales ni rutas construidas a partir de la
  entrada del usuario), con límites de tamaño en contratos y archivos comprimidos; cada generación se
  audita y se contabiliza (`axiz.initializr.generations` por arquetipo, estilo, stack y resultado).
- Se despliega con su propio chart (`axiz-initializr/deploy/helm`), con la configuración en ConfigMaps
  y los secretos de cliente desde el ExternalSecret.

## Releases

Los tags `axiz-tools-v*` construyen el artefacto JVM de la CLI, los componentes nativos firmados para
los tres sistemas operativos admitidos, las recetas y la imagen del Initializr. `ci.yaml` no publica
una release ni despliega un ambiente. Un build manual puede solicitar componentes nativos con
`native-cli` antes de crear un tag. La versión de las recetas debe coincidir con la versión del
framework hacia la que actualizan.

`deploy.yaml` promueve de forma independiente el producto Maven verificado y publica el borrador de
release preparado, incluidos los archivos de la CLI y los alias SHA-256. `deploy-initializr.yaml`
promueve de forma independiente la imagen y el chart del Initializr mediante GitOps. Ambos requieren
la ejecución productora completada, el intento, el hash del manifiesto externo, el ambiente protegido
y el predecesor; el Initializr además requiere un commit completo de values. Los dos productos tienen
hashes de manifiesto distintos, incluso en una sola ejecución de CI.

Los builds leen las dependencias Maven con `GITHUB_TOKEN` (`packages: read`) o con los opcionales
`PACKAGES_READ_USERNAME` / `PACKAGES_READ_TOKEN` para acceder a otros repositorios. La promoción Maven
y la publicación en GHCR usan el token del job con `packages: write`; el despliegue de servicios lee
GHCR y usa credenciales de GitOps separadas. Ver la [configuración del registro](../.github/docs/github-packages.md).
`AXIZ_JVM_BASE_IMAGE` y `AXIZ_NATIVE_BASE_IMAGE` se configuran como variables del repositorio fijadas
por digest. Los candidatos opcionales del framework se pasan a los builders JVM, nativo y del
Initializr mediante las mismas entradas de repositorio, ejecución, intento y hash.

El contrato completo está en [la documentación de pipelines](../.github/docs/pipelines.md).

`deploy-initializr.yaml` también recibe `workflow_run` del CI y resuelve un comprobante firmado
específico de `axiz-initializr`. El modo automático promueve a dev desde el HEAD protegido de main;
el manual conserva los inputs externos. `GITOPS_READ_TOKEN` se configura con lectura del repositorio
GitOps para observar el predecesor; las credenciales de escritura siguen en el deploy protegido.
