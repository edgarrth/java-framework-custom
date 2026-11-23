# Publicación de Axiz

El repositorio `edgarrth/java-framework-custom` publica cuatro componentes con versiones
independientes. La demo es un consumidor del framework y no se publica como SDK.

| Directorio | Componente | Formato del tag | Qué publica el tag |
|---|---|---|---|
| `axiz-framework` | Framework y motor de generación | `axiz-framework-vX.Y.Z` | Artefactos Maven en GitHub Packages (`framework.yaml`) |
| `axiz-tools` | CLI, Initializr y recetas | `axiz-tools-vX.Y.Z` | Artefactos Maven, imagen del Initializr y binarios nativos de la CLI (`tools.yaml`) |
| `axiz-docs` | Sitio de documentación | `axiz-docs-vX.Y.Z` | Sitio en GitHub Pages (`docs.yaml`) |
| `.github` | Workflows reutilizables | `axiz-workflows-vX.Y.Z` | Release que fijan los servicios y librerías generados |

Todo artefacto publicado lleva su atestación de procedencia de GitHub y se verifica con
`gh attestation verify`.

## Configuración del repositorio

- **Rama de publicación:** `main`, protegida con un ruleset que exige pull request, la aprobación
  de un code owner y los checks `axiz-workflows-policy`, `axiz-security-pr` y `axiz-docs-site-pr`.
- **Releases inmutables:** activas en Settings > General > Releases, de modo que un tag publicado
  nunca cambia de contenido.
- **Secret scanning:** con push protection; la revisión de dependencias y CodeQL corren en `security.yaml`.
- **Ambientes:** `dev`, `qa` y `prod` con sus reglas de protección (revisores requeridos y ramas
  permitidas), y `github-pages` para el sitio.

## GitHub Apps

| Rol | Workflow | Permisos de repositorio | Configuración |
|---|---|---|---|
| Release del monorepo | `release-self.yaml` | Contents, Pull requests y Workflows: escritura | variable `RELEASE_APP_CLIENT_ID`, secreto `RELEASE_APP_PRIVATE_KEY` |
| Release de consumidores | `release.yaml` | Contents y Pull requests: escritura | secretos `RELEASE_APP_CLIENT_ID` y `RELEASE_APP_PRIVATE_KEY` |
| GitOps | `service-deploy.yaml` | Contents: escritura, solo en el repositorio GitOps | secretos `GITOPS_APP_CLIENT_ID` y `GITOPS_APP_PRIVATE_KEY`, variable `AXIZ_GITOPS_REPOSITORY` |

Los tags se crean con el token de la App y no con `GITHUB_TOKEN`, porque los eventos creados
con `GITHUB_TOKEN` no disparan otros workflows y el tag debe disparar la publicación.

## Flujo de una versión

1. Los commits siguen Conventional Commits; release-please mantiene un pull request de release
   por componente con la versión siguiente y el changelog.
2. Al fusionar ese pull request, release-please crea el tag y la release del componente.
3. El tag dispara el workflow del producto, que verifica y publica con atestaciones. Las
   releases del framework y de las herramientas se crean como borrador y se publican cuando sus
   artefactos ya están disponibles.
4. Con la release de workflows, release-please actualiza también el tag `axiz-workflows-v*`
   que usan las plantillas del generador y los servicios de la demo.

`release-please-config.json` y `.release-please-manifest.json` son el estado de publicación;
`bootstrap-sha` fija el inicio del historial de la primera versión. La compatibilidad de la API
pública se comprueba con japicmp contra la última versión publicada; ver la
[política de API](axiz-framework/docs/api/README.md).

## Primera publicación

Los productos del monorepo llaman a los workflows reutilizables desde el mismo commit
(`./.github/workflows/...`), así que todos los checks funcionan antes de que exista cualquier
release. El orden recomendado es publicar primero la release de workflows (para los consumidores
externos), luego el framework y después las herramientas y la documentación.

## Mantenimiento de versiones

Renovate mantiene las dependencias con `renovate.json`: agrupa plugins y librerías de prueba,
fija las acciones por SHA con el comentario de su versión y las imágenes por digest. Los
servicios generados usan el preset `.github/renovate/service.json`, que solo actualiza el parent
de Axiz. Las referencias del monorepo a sí mismo las mueve release-please, no Renovate.
