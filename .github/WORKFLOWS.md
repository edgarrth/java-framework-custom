# Workflows

Los pipelines usan funciones nativas de GitHub y herramientas estándar: atestaciones de
artefactos para procedencia y SBOM, CodeQL, revisión de dependencias, secret scanning con push
protection, ambientes con reglas de protección y release-please. No hay acciones propias; los
pocos scripts que quedan son pruebas de producto del framework.

## Workflows reutilizables

Los consumen los servicios y librerías generados, fijados al tag inmutable de la release de
workflows (`axiz-workflows-vX.Y.Z`). El contrato completo está en [pipelines](docs/pipelines.md).

| Workflow | Uso |
|---|---|
| `lib-build.yaml` | Verifica un proyecto Maven y, si se pide, lo publica en GitHub Packages con procedencia atestiguada |
| `service-build.yaml` | Verifica un servicio, publica imagen y chart en GHCR y atestigua procedencia y SBOM |
| `service-deploy.yaml` | Verifica la atestación de un digest y lo despliega en un ambiente por GitOps |
| `release.yaml` | Pull request de release y tags con release-please |
| `framework-snapshot.yaml` | Uso interno del monorepo: compila el framework una vez por run y lo comparte como artefacto |

## Workflows del monorepo

| Workflow | Cuándo corre | Qué hace |
|---|---|---|
| `framework.yaml` | Cambios en `axiz-framework`; tag `axiz-framework-v*` | Build con calidad repartido por capas en jobs paralelos, compatibilidad de API con japicmp, ejemplos generados; publica con el tag |
| `framework-nightly.yaml` | Cada noche | Todas las combinaciones generadas, ejemplos nativos y aceptación reactiva nativa |
| `tools.yaml` | Cambios en `axiz-tools` o el framework; tag `axiz-tools-v*` | CLI, Initializr y recetas; con el tag publica artefactos, imagen y binarios nativos |
| `demo.yaml` | Cambios en `axiz-demo` o el framework | Cada servicio con `service-build`; cobertura de capacidades |
| `demo-deploy.yaml` | Manual | Promueve un digest atestiguado de un servicio a dev, qa o prod |
| `demo-nightly.yaml` | Días hábiles | Demo completa en kind con prueba de humo y DAST, e imágenes nativas |
| `docs.yaml` | Cada pull request; tag `axiz-docs-v*` | Referencia al día, lint y build estricto; publica en Pages con el tag |
| `security.yaml` | Cada pull request, main y semanal | CodeQL, revisión de dependencias, Trivy, reglas Semgrep de Axiz e imágenes locales |
| `workflows.yaml` | Cada pull request | actionlint y zizmor sobre los workflows |
| `release-self.yaml` | Cada push a main | release-please del monorepo |

## Checks requeridos

El ruleset de `main` exige los jobs de cierre `axiz-workflows-policy`, `axiz-security-pr` y
`axiz-docs-site-pr`, que corren en todos los pull requests. `axiz-framework-pr`, `axiz-tools-pr`
y `axiz-demo-pr` corren cuando el cambio toca su producto y pueden exigirse también.

## Otros archivos

- `renovate/service.json`: preset de Renovate para los servicios generados.
- `gitops/`: plantillas de Argo CD y la política de admisión de Kyverno.
- `security/`: datos de Trivy y declaraciones VEX revisadas.
- `scripts/`: sincronización de las recetas de contenedores locales y generación de la receta de
  WireMock desde su lock; `requirements.txt` fija por hash sus dependencias.
- `zizmor.yml`: configuración del auditor de workflows.
