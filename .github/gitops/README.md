# GitOps

Plantillas para el despliegue de los servicios con Argo CD y la admisión con Kyverno. Antes de
aplicarlas se reemplazan `OWNER` (la cuenta u organización de GitHub) y `GITOPS_REPOSITORY` (el
repositorio GitOps).

| Archivo | Uso |
|---|---|
| `argocd/applicationset.yaml.template` | Una Application por cada archivo `environments/<ambiente>/<servicio>.yaml` |
| `argocd/project.yaml.template` | Proyecto con fuentes, destinos y tipos de recurso permitidos |
| `policies/verify-images.yaml.template` | Admite solo imágenes de GHCR por digest con la procedencia de `service-build` |

## Flujo

1. El workflow `service-build` construye la imagen con Jib en `ghcr.io/<owner>/<servicio>`,
   publica el chart en `oci://ghcr.io/<owner>/charts/<servicio>` y atestigua procedencia y SBOM
   con las atestaciones de artefactos de GitHub.
2. El workflow `service-deploy` verifica la atestación con `gh attestation verify` y escribe
   `environments/<ambiente>/<servicio>.yaml` en el repositorio GitOps.
3. Argo CD aplica el chart de esa versión con `values-<ambiente>.yaml` y la imagen por digest.
4. Kyverno vuelve a verificar la procedencia en la admisión de cada Pod.

El archivo de cada ambiente tiene esta forma:

```yaml
service: payment-orders
environment: qa
image:
  repository: ghcr.io/owner/payment-orders
  digest: sha256:...
chart:
  oci: oci://ghcr.io/owner/charts/payment-orders
  version: 2.0.0
source:
  repository: owner/repo
  run: https://github.com/owner/repo/actions/runs/...
```

Promover a otro ambiente o volver atrás es desplegar otro digest ya construido; las reglas de
protección de cada ambiente de GitHub (aprobaciones, ramas permitidas) se aplican antes del
despliegue. La verificación manual de una imagen se hace con:

```bash
gh attestation verify oci://ghcr.io/<owner>/<servicio>@sha256:<digest> --repo <owner>/<repo> \
  --signer-workflow edgarrth/java-framework-custom/.github/workflows/service-build.yaml
```
