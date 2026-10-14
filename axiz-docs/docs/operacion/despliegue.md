# Despliegue GitOps

## El chart del servicio

Cada servicio trae su chart en `deploy/helm/<servicio>` con `values.yaml` (por defecto) y
`values-dev.yaml`, `values-qa.yaml`, `values-prod.yaml`:

| Recurso | Detalle |
|---|---|
| `Deployment` (o `CronJob` en batch) | usuario no root, filesystem de solo lectura, sin privilegios, probes de liveness/readiness/startup, apagado ordenado, checksum de los ConfigMaps (un cambio dispara rollout), imagen por tag y digest |
| `Service` | ClusterIP (o NodePort para pruebas locales) |
| `HorizontalPodAutoscaler`, `PodDisruptionBudget` | réplicas por ambiente; el PDB usa `maxUnavailable` para no bloquear el drenado de nodos |
| `ConfigMap` `<servicio>` y `<servicio>-errors` | configuración del ambiente y catálogo de errores |
| `ExternalSecret` | llaves del servicio desde Secrets Manager (`<ambiente>/<servicio>/<llave>`) montadas en `/var/run/secrets/axiz` |
| `ServiceAccount`, `Role`, `RoleBinding` | IRSA/Pod Identity por ambiente; lectura de ConfigMaps del namespace |
| `NetworkPolicy` | entrada solo desde el mismo namespace y los namespaces listados (ingress, monitoreo) |

CI valida cada combinación `values.yaml` + `values-<env>.yaml` con `helm lint`, kubeconform y
kube-linter.

## Cómo llega a un ambiente

El pipeline **nunca** ejecuta `helm` contra un clúster. `service-cd` escribe el release en el
repositorio GitOps y Argo CD lo aplica:

```yaml
# axiz-gitops/environments/qa/payments-business.yaml (escrito por service-cd)
service: payments-business
environment: qa
source:
  repoURL: https://github.com/axiz/payments-business
  revision: 3f9c2e…        # commit construido: chart, values y código
  chart: deploy/helm/payments-business
image:
  tag: "3f9c2e…"
  digest: "sha256:…"
deployedBy: "ana"
run: "https://github.com/axiz/payments-business/actions/runs/…"
```

Un `ApplicationSet` de Argo CD crea una Application por archivo (`<servicio>-<ambiente>`) con el
chart del repositorio del servicio en ese commit, `values.yaml` + `values-<ambiente>.yaml` y la
imagen del archivo, con sincronización automática, prune y self-heal. Las referencias están en
`axiz-workflows/gitops/` (ApplicationSet, AppProject con los namespaces y tipos de recursos
permitidos).

- Cada despliegue es un commit: queda auditado en Git y un **rollback es un revert** (o
  `cd.yaml` con la imagen anterior).
- `prod` requiere la aprobación del environment de GitHub antes de escribir el release.
- Con `ARGOCD_SERVER` definido, el workflow espera a que la Application quede sincronizada y sana.

## Imágenes firmadas

CI firma cada imagen con **cosign** usando una llave de AWS KMS (sin llaves en archivos) y adjunta
el SBOM como atestación. En el clúster, la política de Kyverno
(`gitops/policies/verify-images.yaml`) solo admite en los namespaces de servicios imágenes del
registro de Nexus firmadas con esa llave; el chart fija la imagen por digest.

## Configuración y secretos por ambiente

Ver [Configuración y secretos](../arquitectura/configuracion.md). Un namespace por ambiente
(`axiz-dev`, `axiz-qa`, `axiz-prod`), mismos nombres de ConfigMaps en todos; el ConfigMap
compartido `axiz-common` lo administra plataforma.

## Perfil operativo

El chart define `AXIZ_ENVIRONMENT` (`dev`, `qa`, `prod`) y el framework lo usa como perfil
operativo. Un estado que vive en un solo proceso solo es correcto con una réplica y sin reinicios.
Por eso solo se acepta en `local`, `test` y `ci`. En cualquier otro ambiente, el servicio no
arranca si su configuración lo usa:

| Capacidad | En memoria (solo local/test/ci) | Ambiente desplegado |
|---|---|---|
| Idempotencia HTTP | `axiz.idempotency.store=memory` | `redis` o `dynamodb` |
| Locks | `axiz.lock.store=memory` | `redis` o `dynamodb` |
| Archivos | `axiz.storage.type=memory` | `s3` |

La tabla siguiente muestra qué pasa cuando cae una dependencia. El criterio es que ninguna
garantía declarada se degrade sin dejar rastro:

| Dependencia caída | Comportamiento |
|---|---|
| Store de idempotencia | La solicitud falla antes de ejecutarse: no se ejecuta sin protección. Si cae al terminar, la respuesta llega al cliente y la llave vence sola. |
| Inbox | Fallo transitorio: el mensaje no se confirma y se vuelve a entregar. |
| Broker (Kafka, SNS/SQS) | Los eventos se acumulan en el outbox (`axiz_outbox_pending`) y el relay reintenta. El cambio de negocio ya está confirmado. |
| Lock | La operación protegida falla: no corre sin exclusión. |
| Saga: participante sin respuesta | El paso queda `IN_DOUBT` y la recuperación lo verifica. No se compensa lo que no se sabe si ocurrió. |
| Auditoría durable | Viaja con el outbox de la transacción. Los eventos asíncronos se reintentan y se cuentan en `axiz.audit.lost` si se pierden. |
| Caché remota (Redis) | Se degrada a la fuente de verdad. |
| Rate limit en Redis | `axiz.security.rate-limit.fail-open` (por defecto `true`). En operaciones sensibles, define `false`. |
| Rate limit en memoria | Se aplica por réplica, así que el límite efectivo es N veces el configurado. Es aceptable como protección básica, no como cuota contractual. |
