# Publicación de Axiz

El repositorio `edgarrth/java-framework-custom` publica cuatro componentes con versiones
independientes. La demo es un consumidor del framework y no se publica como SDK.

| Directorio | Componente | Formato del tag |
|---|---|---|
| `axiz-framework` | Framework y motor de generación | `axiz-framework-vX.Y.Z` |
| `axiz-tools` | CLI, Initializr y recetas | `axiz-tools-vX.Y.Z` |
| `axiz-docs` | Sitio de documentación | `axiz-docs-vX.Y.Z` |
| `.github` | Workflows, acciones y políticas | `axiz-workflows-vX.Y.Z` |

## Configuración del repositorio

La rama de publicación es `main`. Configurar releases inmutables, protección de ramas,
CODEOWNERS, entornos protegidos y los secretos usados por cada workflow. Las credenciales
se suministran desde GitHub y los gestores de secretos, nunca desde archivos versionados.

`release-please-config.json` y `.release-please-manifest.json` son el estado de publicación.
`bootstrap-sha` fija el límite exclusivo del historial usado para la primera versión.
Los mensajes Conventional Commits determinan las siguientes versiones. La publicación inicial
del framework usa 2.0.0 y la política de [API pública](axiz-framework/docs/api/README.md).

## Permisos de las GitHub Apps

| Rol | Workflow que solicita el token | Permisos de repositorio |
|---|---|---|
| Release del monorepo | `.github/workflows/release-self.yaml` | Contents, Pull requests y Workflows: escritura |
| Release de consumidores | `.github/workflows/release.yaml` | Contents y Pull requests: escritura |
| GitOps | `.github/workflows/service-deploy.yaml` | Contents: escritura, restringido al repositorio destino |

Metadata de lectura es implícito. La instalación debe conceder los permisos que solicita
cada token; los tokens efímeros se limitan al rol y repositorio de destino. Los secretos de
release son `RELEASE_APP_CLIENT_ID` y `RELEASE_APP_PRIVATE_KEY`; GitOps usa
`GITOPS_APP_CLIENT_ID` y `GITOPS_APP_PRIVATE_KEY`.

En Settings → Secrets and variables → Actions, guardar `RELEASE_APP_CLIENT_ID` como variable
de repositorio y `RELEASE_APP_PRIVATE_KEY` como secreto. El Client ID también puede guardarse
como secreto. La App debe estar instalada en este repositorio y su instalación debe tener los
permisos de la tabla. `release-self` comprueba ambos valores antes de solicitar un token; nunca
usa el token automático de Actions como sustituto de la App.

El monorepo necesita Workflows porque Release Please actualiza siete workflows publicados.
Los consumidores no reciben ese permiso ni un input para ampliarlo. `permission-workflows`
es un input de `create-github-app-token`, no un permiso de `GITHUB_TOKEN`. Las operaciones de
comentarios y etiquetas del release utilizan Pull requests: escritura; no necesitan Issues:
escritura. GitOps recibe owner y repositorio desde la configuración validada del destino.

`release_permissions.py` comprueba permisos y destinos explícitos. `extra-files` propios
reemplazan los heredados, incluso con una lista vacía; las rutas se resuelven desde el paquete
o la raíz. El contrato no admite `glob: true`. Las rutas de workflows declaradas se contrastan
con todas las actualizaciones del planificador real, aunque su contenido no cambie todavía.
Una divergencia bloquea el control; los permisos no se amplían automáticamente.

## Primera publicación

1. Configurar Nexus, el registro OCI, las GitHub Apps, los entornos protegidos y GitHub Pages
   según los productos que se publiquen.
2. Exigir inicialmente `axiz-workflows-policy`, `axiz-security-pr` y `axiz-docs-site-pr`.
3. En Actions → release-self → Run workflow, seleccionar `main`. Esto también permite reintentar
   después de configurar las credenciales, sin crear un commit vacío. Revisar y fusionar primero
   el PR de release de `axiz-workflows`; la siguiente ejecución de `release-self` publica su tag
   y release. Habilitar previamente las releases inmutables en Settings → General → Releases.
   El manifiesto vacío representa componentes sin publicaciones previas.
4. Verificar que el guard `axiz-workflows-trust` acepta esa release. Activar los checks de
   framework, herramientas, demo y documentación.
5. Publicar el framework antes de las herramientas que necesitan resolver sus artefactos.
   Publicar después herramientas y documentación mediante sus PR de release independientes.

Los guards consultan la API de releases y exigen tag exacto, `immutable: true` y `draft: false`.
Un tag local no sustituye una release inmutable publicada. Para repositorios privados,
`AXIZ_WORKFLOWS_READ_TOKEN` concede solo lectura al repositorio de workflows.

Antes de esa primera publicación, GitHub no puede resolver las llamadas a la release de
workflows de framework, tools y demo: valida las referencias antes de ejecutar los
jobs, incluso si tienen una condición `if`. Completar primero los controles independientes
`workflows-ci`, `security` y `docs-docs`, publicar los workflows y después ejecutar los CI de los
productos. Un tag vacío o una referencia a `main` no cumple el contrato de publicación.

Los scripts se ejecutan por rutas locales. GitHub también admite llamadas locales entre
workflows del mismo commit; se usan para controles internos. Los builds y deploys firmados
consumen la release inmutable de workflows porque el manifiesto y sus verificadores exigen
esa identidad. Cambiar esas llamadas requiere adaptar y validar también el contrato de firma.

## Build y deploy

Build recibe commit, versión y parámetros verificables, ejecuta sus controles y produce
artefactos, SBOM, manifiesto y firmas. Deploy recibe la ejecución y el intento productores,
un hash externo aprobado, ambiente, destino y predecesor. Verifica y promueve esos mismos
bytes; no recompila ni vuelve a empaquetar el producto.

Framework y herramientas preparan releases borrador. Sus deploys promueven el producto Maven
verificado y publican la release con los assets correspondientes. Initializr promueve su imagen
y chart por un deploy independiente. Servicios y rollback usan el manifiesto firmado de un
build existente y una revisión completa de values.

El [contrato de pipelines](.github/docs/pipelines.md) detalla entradas, salidas, retención,
firmas, separación de credenciales, promoción y rollback. La
[guía de GitOps](.github/gitops/README.md) describe los destinos.

## Publicación y adopción de workflows

Release Please actualiza únicamente las autorreferencias del producto marcadas con
`# x-release-please-version`. Los consumidores conservan la versión anterior durante el PR
de producto y la ventana posterior a su publicación.

Renovate propone después un único PR `axiz workflows` que actualiza `uses:`, guards, checkouts,
plantillas y presets consumidores. Todas las referencias consumidoras adoptan la misma versión.
Los tags publicados no se mueven ni reutilizan: una corrección produce una versión nueva.

## Checks requeridos

| Check | Activación |
|---|---|
| `axiz-workflows-policy` | Desde el arranque |
| `axiz-security-pr` | Desde el arranque |
| `axiz-docs-site-pr` | Desde el arranque |
| `axiz-framework-pr` | Tras publicar la primera release de workflows |
| `axiz-tools-pr` | Tras publicar la primera release de workflows |
| `axiz-demo-pr` | Tras publicar la primera release de workflows |
| `axiz-docs-pr` | Tras publicar la primera release de workflows |

Los veredictos agregados rechazan fallos, cancelaciones y omisiones no declaradas.
`.github/policies/required-checks-v2.json` registra jobs y excepciones exactas. Los checks
se vinculan a GitHub Actions y a la revisión de CODEOWNERS. Si se habilita merge queue,
los workflows requeridos deben atender también `merge_group`.

## Controles locales

El inventario común está en `.github/scripts/workflows_policy_checks.py`. Usa el Node fijado
en `.github/scripts/release-version/.node-version`, los locks npm y Python y esquemas CRD
verificados por hash. Instalar las herramientas con `npm ci --include=dev --ignore-scripts`
y `pip install --require-hashes` según los workflows.

CI prepara los informes de los tres locks npm y la metadata de Release Please. Las variables
`AXIZ_NPM_AUDIT_INPUT` y `AXIZ_RELEASE_ACTION_CACHE` apuntan a esas entradas verificadas.
El inventario ejecuta comprobaciones offline y conserva los resultados en el directorio de
salida indicado. `release_entrypoints.py` valida el ciclo en copias Git aisladas: PR de
producto, ventana posterior al tag y adopción. El checkout de origen permanece intacto.

Los contratos de jobs privilegiados se actualizan explícitamente con
`record_privileged_roles.py --write` después de revisar el cambio. CI verifica el registro,
sin aprobarlo o modificarlo automáticamente.

## Mantenimiento de versiones

El preset común es `.github/renovate/default.json`; las variantes publicadas se generan con
`python .github/scripts/build_renovate_presets.py --write`. Los gestores de acciones de terceros
comparten el filtro de tags completos del gestor nativo. Las pruebas usan la búsqueda real
de Renovate antes de reemplazar referencias y regenerar archivos.

Los pins documentales se obtienen de `.github/policies/action-pins-v2.json`. Después de revisar
una actualización, verificar SHA, tag, metadata e inputs; ejecutar `check_action_pins.py --refresh`
y `render_docs_controls.py`. La regeneración debe conservar la versión nueva.

Ambos roles de release usan el mismo SHA de Release Please. Para actualizarlo, ejecutar
`release_action.py refresh --cache <directorio>` con el Node fijado y revisar el registro y
los dos locks. El grupo `release planner toolchain` mantiene esas dependencias coordinadas.

## Documentación publicada

GitHub Pages se construye desde `axiz-docs`. El build y la preparación tienen solo lectura;
un publicador protegido verifica el bundle y otro rol despliega Pages. La documentación
[del sitio](axiz-docs/README.md) explica generación, configuración y recuperación.
