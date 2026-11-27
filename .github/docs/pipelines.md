# Contrato de los pipelines

Los servicios y librerías generados llaman a los workflows reutilizables de Axiz fijados por el
tag de su release, por ejemplo
`edgarrth/java-framework-custom/.github/workflows/service-build.yaml@axiz-workflows-v2.0.0`. Las
releases de workflows son inmutables, de modo que el tag identifica siempre el mismo contenido;
release-please actualiza ese tag en las plantillas del generador.

## service-build

| Entrada | Por defecto | Descripción |
|---|---|---|
| `service` | requerida | Nombre del servicio, de su imagen y de su chart |
| `working-directory` | `.` | Raíz del proyecto Maven |
| `module` | `.` | Módulo Maven del servicio en un proyecto multimódulo |
| `chart` | `<module>/deploy/helm/<service>` | Directorio del chart |
| `java-version` | `25` | Versión de Java |
| `native` | `false` | Compila con GraalVM y publica la imagen nativa |
| `platforms` | `linux/amd64` | Plataformas de la imagen |
| `base-image` | la del POM | Imagen base fijada por digest |
| `setup-command` | vacío | Comando previo a la compilación |
| `maven-artifact` | vacío | Artefacto del mismo run con un fragmento del repositorio Maven local, por ejemplo el framework del mismo commit |
| `publish` | `false` | Publica imagen y chart con atestaciones |

El job `verify` compila con el perfil `quality` y ejecuta las pruebas. En los pull requests
compara el contrato OpenAPI con la rama destino (oasdiff) y falla ante un cambio incompatible.
Un cambio incompatible intencional se acepta de forma explícita en
`src/main/openapi/breaking-changes.txt`, junto al contrato: una línea por cambio con el método,
la ruta y el texto que reporta oasdiff. El archivo pasa por la revisión del code owner, y los
contratos nunca se suben a servicios externos. Además, valida el chart para cada
`values*.yaml` (`helm lint --strict` y kubeconform) y escanea el SBOM del grafo de dependencias
resuelto con Trivy. Una vulnerabilidad MEDIUM, HIGH o CRITICAL rompe el build. Las excepciones
se declaran en `.trivyignore.yaml` con fecha de vencimiento y justificación, y las revisa el
code owner.

El job `publish` construye la imagen con Jib directamente en `ghcr.io/<owner>/<service>`,
etiquetada con el commit y la versión, y la escanea por digest. Luego publica dos atestaciones
en el registro: la procedencia SLSA (`actions/attest-build-provenance`) y el SBOM CycloneDX
(`actions/attest-sbom`). El chart se publica en `oci://ghcr.io/<owner>/charts/<service>` con la
versión del POM. Las salidas `image`, `digest`, `chart` y `version` alimentan el despliegue.

Permisos del llamador: `contents: read`, `packages: write`, `id-token: write` y
`attestations: write`.

## Artefactos Maven del mismo run

`maven-artifact` recibe el nombre de un artefacto del mismo run cuya raíz es la de un repositorio
Maven local, y lo copia a `~/.m2/repository` antes de compilar. En el monorepo,
`framework-snapshot.yaml` compila el framework una sola vez y lo comparte así con los servicios de
la demo y las herramientas, en lugar de que cada job lo recompile y descargue de nuevo sus
dependencias.

## Dependencias de Maven Central

Maven Central limita las descargas por IP y responde 429 a los runners compartidos. Cada job que
ejecuta Maven declara, con un settings global (`-gs` en `MAVEN_ARGS`), el espejo oficial de Maven
Central en Google Cloud Storage como primer repositorio; Central queda de respaldo para lo que
todavía no llega al espejo. Las descargas se verifican con los mismos checksums en ambos casos.

## lib-build

| Entrada | Por defecto | Descripción |
|---|---|---|
| `working-directory` | `.` | Raíz del proyecto Maven |
| `java-version` | `25` | Versión de Java |
| `bootstrap` | `false` | Ejecuta el wrapper con `--bootstrap` |
| `maven-args` | vacío | Argumentos adicionales de la verificación |
| `setup-command` | vacío | Comando previo |
| `maven-artifact` | vacío | Igual que en `service-build` |
| `modules` | vacío | Directorios a construir, separados por espacios; vacío construye todo el proyecto |
| `cache-key` | vacío | Distingue el caché de dependencias de varias llamadas sobre el mismo proyecto |
| `maven-threads` | vacío | Módulos en paralelo (`-T`), por ejemplo `1C` |
| `scan-dependencies` | `true` | Escanea el SBOM del proyecto completo |
| `verify` | `true` | Ejecuta la verificación; se omite al publicar lo que ya verificó otra llamada |
| `publish` | `false` | Despliega en GitHub Packages |

`verify` compila con el perfil `quality` y escanea el SBOM con Trivy. Con `modules`, varias llamadas
en una matriz reparten un proyecto grande en jobs paralelos; los módulos que no forman parte de la
selección se resuelven del repositorio local, por ejemplo desde `maven-artifact`. `publish` ejecuta
`mvn -Prelease deploy` hacia GitHub Packages y atestigua la procedencia de cada jar.

## service-deploy

| Entrada | Por defecto | Descripción |
|---|---|---|
| `service` | requerida | Servicio a desplegar |
| `environment` | requerida | Ambiente de GitHub y de GitOps |
| `digest` | requerida | Digest de la imagen ya construida |
| `version` | requerida | Versión del chart |
| `image` | `ghcr.io/<owner>/<service>` | Repositorio de la imagen |
| `chart` | `oci://ghcr.io/<owner>/charts/<service>` | Repositorio OCI del chart |
| `gitops-repository` | el mismo repositorio | Repositorio GitOps `owner/name` |

El job corre en el ambiente de GitHub indicado, de modo que sus reglas de protección
(revisores requeridos, ramas permitidas, tiempo de espera) se aplican antes de cualquier paso.
`gh attestation verify` comprueba que el digest tiene la procedencia firmada por el workflow
`service-build` de Axiz desde el repositorio del servicio. Después, el job escribe
`environments/<ambiente>/<servicio>.yaml` en el repositorio GitOps con un token de GitHub App
(`GITOPS_APP_CLIENT_ID`, `GITOPS_APP_PRIVATE_KEY`) limitado a ese repositorio. Argo CD aplica el
cambio y Kyverno vuelve a verificar la procedencia al admitir cada Pod; ver [GitOps](../gitops/README.md).

Promover a qa o prod es ejecutar el despliegue con el mismo digest que ya pasó por dev. Volver
atrás es desplegar el digest anterior. Ninguna de las dos operaciones recompila.

## Verificación manual

```bash
gh attestation verify oci://ghcr.io/<owner>/<servicio>@sha256:<digest> --repo <owner>/<repo> \
  --signer-workflow edgarrth/java-framework-custom/.github/workflows/service-build.yaml
gh attestation verify target/<artefacto>.jar --repo edgarrth/java-framework-custom
```
