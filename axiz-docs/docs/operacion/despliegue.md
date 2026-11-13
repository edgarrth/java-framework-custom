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

CI valida cada combinación `values.yaml` + `values-<env>.yaml` con `helm lint`, kubeconform y
kube-linter.

## Cómo llega a un ambiente

El pipeline **nunca** ejecuta `helm` contra un clúster. `service-deploy` escribe el release en el
repositorio GitOps y Argo CD lo aplica:

El workflow `service-deploy` verifica el bundle de un build anterior mediante su run, intento
y hash externo del manifiesto firmado. La imagen y el chart se consumen por digest. La revisión
de values es un commit completo e inmutable; no se reconstruye el chart desde una rama móvil.
El registro GitOps conserva esos identificadores, el environment, el ejecutor y el despliegue
anterior esperado. Argo CD aplica el estado verificado y el workflow exige sincronización y salud.

La promoción a qa/prod y el rollback seleccionan el manifiesto correspondiente y pasan por el
mismo flujo de verificación y aprobación. Cambiar un tag o recompilar no forma parte del deploy.
Los permisos de escritura GitOps pertenecen exclusivamente al deploy; el build carece de ellos.

## Imágenes firmadas

El build firma los digests con **cosign sin llave**, usando el token OIDC del job de firma,
Fulcio y Rekor, y adjunta las atestaciones de SBOM y procedencia. No requiere una llave KMS para
firmar. La política Kyverno (`.github/gitops/policies/verify-images.yaml.template`) verifica el issuer
`https://token.actions.githubusercontent.com`, la identidad
`https://github.com/edgarrth/java-framework-custom/.github/workflows/service-build.yaml@refs/tags/axiz-workflows-vX.Y.Z`
y la transparencia de Rekor. La versión de workflows adoptada determina la identidad exacta.
El deploy exige los digests verificados del manifiesto. Las imágenes base se fijan por tag y digest;
Renovate actualiza ambos.

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

Las plantillas GitOps se renderizan antes de aplicarse usando la misma configuración JSON
`BUILD_DESTINATION` del build y del firmador. Esta define autoridad del registro, namespace,
repositorios exactos y servicios permitidos. Deploy, rollback y ApplicationSet comparten la ruta
`environments/<ambiente>/<servicio>.yaml` desde la raíz del repositorio GitOps. Véase
[la configuración de destinos](https://github.com/edgarrth/java-framework-custom/blob/main/.github/gitops/README.md).
