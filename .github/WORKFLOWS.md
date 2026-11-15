# Workflows y entrega

Workflows reutilizables, acciones, políticas y configuración GitOps de Axiz.
Los proyectos del monorepo y los consumidores generados usan contratos de build y deploy
independientes. El detalle de parámetros y artefactos está en [pipelines](docs/pipelines.md).

## Workflows reutilizables

| Archivo | Responsabilidad |
|---|---|
| `pr.yaml` | Título de PR, secretos, dependencias, análisis estático y contratos |
| `lib-build.yaml` | Construcción Maven, calidad, contratos, SBOM y manifiesto firmado |
| `lib-deploy.yaml` | Promoción de los artefactos Maven verificados y publicación de la release |
| `service-build.yaml` | Construcción de imagen y chart, contratos y manifiesto firmado |
| `service-deploy.yaml` | Promoción o rollback de un build existente mediante GitOps y Argo CD |
| `native.yaml` | Componentes nativos Linux x64, macOS arm64 y Windows x64 |
| `native-nightly.yaml` | Compilación y pruebas de servicios con GraalVM |
| `release.yaml` | PR y tags de release de los proyectos consumidores |

`release-self.yaml` publica los componentes del monorepo mediante un rol propio. Los
workflows `framework-*`, `tools-*`, `demo-*` y `docs-*` componen los reutilizables para cada
producto. `workflows-ci.yaml` valida las acciones, contratos y configuración compartida.

## Referencias y permisos

Las acciones externas se fijan por SHA completo. Las referencias internas usan un tag exacto
de release y dependen directamente de `axiz-workflows-trust`, que consulta la release y exige
que sea inmutable y no sea borrador. Un repositorio privado utiliza
`AXIZ_WORKFLOWS_READ_TOKEN` de solo lectura.

Los jobs comienzan sin permisos globales y declaran los necesarios. Build no recibe credenciales
de promoción; los jobs de firma y publicación tienen roles cerrados. Los tokens de GitHub App
son efímeros y solicitan solo los permisos de su rol. Consultar la
[matriz de permisos](../RELEASING.md#permisos-de-las-github-apps).

## Artefactos y promoción

El build genera un bundle identificado por el hash del manifiesto. Deploy comprueba la
finalización del productor, intento, firmas, hashes y predecesor. Promueve los mismos bytes;
los artefactos descargados no pueden aportar scripts o controles de ejecución al rol protegido.
Los cambios de ambiente y rollback conservan la revisión de values y la identidad del build.

La [configuración GitOps](gitops/README.md) fija repositorios y servicios autorizados.

## Validaciones

`python .github/scripts/workflows_policy_checks.py --output <directorio>` ejecuta el
inventario común con las herramientas preparadas por CI. Comprueba contratos de workflows,
roles, versiones, documentación, esquemas, acciones, presets y los motores reales de release
y adopción. `release_entrypoints.py` ejecuta los mismos controles en copias Git aisladas.

Las acciones y scripts incluyen pruebas de corrupción, límites, aislamiento y concurrencia.
Los controles de secretos verifican también que las excepciones de hashes no oculten secretos.
Los reportes de build y seguridad se generan durante la ejecución de CI y se conservan como
artefactos del run correspondiente.

## Mantenimiento

- `policies/action-pins-v2.json` fija versiones y commits de acciones externas.
- `policies/release-action-v2.json` vincula Release Please con sus planificadores de desarrollo.
- `policies/privileged-jobs-v2.json` registra los contratos de jobs privilegiados.
- `policies/required-checks-v2.json` declara veredictos y omisiones permitidas.
- `scripts/renovate-presets` y `renovate/default.json` son las fuentes de los presets publicados.

Las actualizaciones requieren revisión y regeneración de sus fuentes. CI comprueba que el
resultado coincida; no amplía permisos ni modifica contratos por su cuenta.
El procedimiento completo está en [RELEASING.md](../RELEASING.md).
