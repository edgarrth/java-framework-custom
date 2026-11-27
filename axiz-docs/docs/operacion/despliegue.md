# Despliegue GitOps

## El chart del servicio

Cada servicio trae su chart en `deploy/helm/<servicio>` con `values.yaml` (por defecto) y
`values-dev.yaml`, `values-qa.yaml`, `values-prod.yaml`:

| Recurso | Detalle |
|---|---|
| `Deployment` (o `CronJob` en batch) | usuario no root, filesystem de solo lectura, sin privilegios, probes de liveness/readiness/startup, apagado ordenado, checksum de los ConfigMaps (un cambio dispara rollout), imagen por tag y digest, ConfigMaps montados como archivos en `/etc/axiz/config`, réplicas repartidas por zona (`topologySpread.whenUnsatisfiable`: `DoNotSchedule` en prod) |
| `Service` | ClusterIP (o NodePort para pruebas locales) |
| `HorizontalPodAutoscaler`, `PodDisruptionBudget` | réplicas por ambiente; el PDB usa `maxUnavailable` para no bloquear el drenado de nodos |
| `ConfigMap` `<servicio>` y `<servicio>-errors` | configuración del ambiente y catálogo de errores |
| `ExternalSecret` | llaves del servicio desde Secrets Manager (`<ambiente>/<servicio>/<llave>`) montadas en `/var/run/secrets/axiz` |
| `ServiceAccount` | IRSA/Pod Identity por ambiente; sin token de la API de Kubernetes montado (`automountServiceAccountToken: false`) y sin `Role` ni `RoleBinding`: el servicio no consulta la API |
| `NetworkPolicy` | denegación por defecto en ambos sentidos. Entrada: el mismo namespace y los namespaces listados (ingress, monitoreo). Salida: DNS, los pods del namespace (`egress.sameNamespace`), los namespaces listados (`egress.namespaces`) y los CIDR con sus puertos (`egress.cidrs`: endpoints de VPC de los servicios de AWS, Aurora, ElastiCache, MSK, socios) que cada `values-<env>.yaml` declara |

CI valida cada combinación `values.yaml` + `values-<env>.yaml` con `helm lint --strict` y
kubeconform.

## Cómo llega a un ambiente

El pipeline **nunca** ejecuta `helm` contra un clúster. `service-build` publica la imagen en
`ghcr.io/<owner>/<servicio>` y el chart en `oci://ghcr.io/<owner>/charts/<servicio>`, con la
procedencia SLSA y el SBOM como atestaciones de GitHub. `service-deploy` corre en el ambiente de
GitHub elegido, verifica la atestación del digest con `gh attestation verify` y escribe
`environments/<ambiente>/<servicio>.yaml` en el repositorio GitOps. Argo CD, con su
ApplicationSet, aplica la versión del chart con `values-<ambiente>.yaml` y la imagen por digest.

La promoción a qa o prod y el rollback despliegan un digest ya construido: el de dev o el
anterior. Las reglas de protección del ambiente (revisores requeridos, ramas permitidas) se
aplican antes del despliegue. Recompilar no forma parte del deploy, y los permisos de escritura
GitOps pertenecen solo a él, mediante una GitHub App limitada al repositorio GitOps.

## Imágenes atestiguadas

Las atestaciones las firma Sigstore sin llave con la identidad OIDC del workflow
`service-build`, y quedan en el registro junto a la imagen. La política Kyverno
(`.github/gitops/policies/verify-images.yaml.template`) admite en los namespaces de los servicios
solo imágenes de GHCR por digest con esa procedencia (issuer
`https://token.actions.githubusercontent.com` y el workflow `service-build` de Axiz como firmante).
Las imágenes base se fijan por tag y digest; Renovate actualiza ambos.

## Configuración y secretos por ambiente

Ver [Configuración y secretos](../arquitectura/configuracion.md). Un namespace por ambiente
(`axiz-dev`, `axiz-qa`, `axiz-prod`), mismos nombres de ConfigMaps en todos; el ConfigMap
compartido `axiz-common` lo administra plataforma.

## Perfil operativo

El chart define `AXIZ_CONFIG_ENVIRONMENT` (`dev`, `qa`, `prod`) y el framework lo usa como perfil
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
| Rate limit en Redis | `axiz.security.rate-limit.rules[].fail-open` es obligatorio en cada regla, sin valor por defecto. |
| Rate limit en memoria | Solo se admite con store explícito `memory` y entorno `local`, `test` o `ci`. En despliegues compartidos se exige `redis`. |

Las plantillas GitOps (ApplicationSet, AppProject y la política de Kyverno) se aplican después de
reemplazar el owner y el repositorio GitOps. Deploy, rollback y ApplicationSet comparten la ruta
`environments/<ambiente>/<servicio>.yaml` desde la raíz del repositorio GitOps y la rama `main`.
Véase [la guía de GitOps](https://github.com/edgarrth/java-framework-custom/blob/main/.github/gitops/README.md).
