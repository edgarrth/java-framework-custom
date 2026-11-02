
El contrato nuevo consta de cuatro workflows reutilizables: `lib-build.yaml`, `lib-deploy.yaml`,
`service-build.yaml` y `service-deploy.yaml`. Build y deploy tienen ejecuciones independientes.
El consumidor del framework ya usa `ci.yaml` para construir y `deploy.yaml` para publicar.
Las plantillas de servicios, librerías y starters generan los dos consumidores independientes.
Tools y los consumidores actuales de la demo ya usan esos contratos; los cuatro exports
anteriores se retiraron en 2.0. La reestructuración de servicios de la demo continúa por separado. No se ha publicado esta versión ni probado
su ejecución remota en GitHub.

## Identidad del build

La entrada fija `source-sha` al commit completo del productor y declara `version`. Las candidatas
usan coordenadas finales, por ejemplo `2.0.0`, con etiqueta informativa `2.0.0/3`.
El build comprueba la revisión inmutable de `axiz-workflows` y los claims OIDC del productor.

`build-manifest.json` se valida contra `schemas/build-manifest-v1.schema.json` y se serializa según
RFC 8785. Su SHA-256 identifica el producto, sus parámetros efectivos, gates y dependencias.
Los bundles de Actions se llaman `build-bundle-<manifest-sha256>`: dos productos de la misma
ejecución pueden coexistir sin reemplazarse. Se conservan durante 90 días.

- Librerías: staging Maven local, JAR/POM/BOM y checksums; el build no escribe en Nexus releases.
  La versión se aplica al reactor y sus referencias internas, manteniendo la herencia Maven y
  las expresiones CI-friendly. Los parents y BOMs externos conservan su versión; no se usa una
  propiedad global `revision` que pueda alterar la versión del framework heredado.
- Servicios: imagen y plataformas por digest, chart OCI empaquetado una sola vez, SBOM y
  procedencia SLSA. Cada chart tiene un namespace de registro con repositorio, run, attempt y
  variante, para conservar candidatas distintas con la misma versión. Se firman la imagen, el
  manifiesto y las attestations de SBOM, procedencia y manifiesto.
  El escaneo de la imagen recorre todas las plataformas declaradas, por el mismo digest aprobado.
- Gates comunes: pruebas, quality, mutación, encapsulación, SBOM y escaneo. En servicios son
  obligatorias además las evidencias de contratos y charts. Los contratos se descubren en
  `src/main/{openapi,asyncapi}/` o `docs/` del módulo; una ubicación ambigua falla. Las reglas
  Spectral pertenecen a la versión exacta de `axiz-code-standards` de la dependencia Axiz.
  El snapshot `.axiz/asyncapi.snapshot.yaml` es obligatorio para AsyncAPI. Los snapshots
  `.axiz/{openapi,asyncapi}.snapshot.yaml` deben coincidir semánticamente con el contrato fuente. AsyncAPI también se compara con el contrato del commit base inmutable
  del PR o del push; un tag/manual usa la versión publicada anterior alcanzable y, si todavía no
  existe, el padre del commit fuente. Sólo la primera publicación carece de contrato anterior.
  Cambiar fuente y snapshot juntos no autoriza una ruptura. Se conservan los tipos publicados
  `.vN`, sólo se agregan campos opcionales y una ruptura exige `.vN+1` en paralelo. El lector
  no resuelve referencias externas y rechaza YAML ambiguo. La retirada definitiva de una versión
  requiere antes una decisión de descontinuación comprobada; este gate no la autoriza automáticamente.
  Las comprobaciones adicionales del consumidor también deben pasar:
  el deploy exige que toda la ejecución productora termine con éxito.

## Componentes nativos del CLI

`native.yaml` construye los tres ejecutables y no publica releases. Cada componente contiene un
archivo determinista, la evidencia de `--version` y `catalog`, metadata canónica y firma keyless.
Verifica formato ELF x64, Mach-O arm64 o PE x64 y rechaza diferencias entre el runner y el target.
Los runners corresponden a las [arquitecturas publicadas por GitHub](https://docs.github.com/en/actions/reference/runners/github-hosted-runners).
El CLI usa `-march=compatibility` para evitar depender de las instrucciones particulares del host.

El consumidor debe esperar a que terminen **todos** los componentes antes de llamar a
`lib-build`, pasando `native-module`, `native-binary` y los tres `native-platforms`. El builder
solo descarga componentes de su ejecución e intento actuales; verifica firmas de
`native.yaml@v2.0.0`, commit, revisión de workflows, parámetros, candidata y dependencia Axiz.
No permite mezclar intentos, omitir plataformas o sobrescribir artefactos/evidencias. Al reintentar
un productor con nativos deben ejecutarse todos sus jobs; los componentes de intentos anteriores
no se reutilizan.

Los archivos se incorporan como classifiers Maven `native-<platform>` del módulo ya construido,
con checksums y un gate `native`. El manifiesto principal firma el inventario y toda la cadena
de evidencia. La promoción conserva esos bytes y prepara aliases `axiz-v<version>-<platform>`
con `.tar.gz` o `.zip` y su `.sha256`, antes de publicar el draft. Un asset existente con contenido
diferente o un manifiesto de otra candidata detiene la publicación; nunca se usa `--clobber`.
El verificador público de builds sigue exigiendo una ejecución productora **completada con éxito**.

## Publicación Maven

`lib-deploy` exige run, attempt, hash externo del manifiesto, ambiente protegido, destino y
manifiesto anterior. Verifica firma, productor, gates y todos los bytes antes de escribir.
Rechaza cualquier archivo ya publicado con contenido diferente. Reutiliza archivos idénticos
y reanuda promociones interrumpidas sin reconstruir.

Por ahora el builder admite versiones finales y promoción a `maven-releases`; la publicación
de snapshots todavía está pendiente. El tag final debe apuntar al commit del manifiesto.
Release-please del framework prepara un draft y crea el tag; la promoción retiene los bytes
originales del manifiesto firmado antes de publicar ese draft. El registro de despliegue firmado
se conserva también en un release de auditoría y como artefacto de Actions.

## Servicios, promoción y rollback

`service-deploy` exige los mismos identificadores del build, `environment`, `values-revision`
completo y `previous-manifest-sha256` (`initial-deployment` en el primer despliegue).
El servicio se obtiene del manifiesto, nunca del commit de la ejecución de deploy.

1. Verifica firma y ejecución productora completada, hash externo, gates, bytes de manifiestos
   OCI, plataformas y metadata del chart.
2. Obtiene `values.yaml` y `values-<environment>.yaml` del repositorio fuente por el commit
   completo autorizado. Rechaza symlinks y registra referencias y hashes, sin copiar valores de
   secretos al registro de despliegue.
3. Comprueba la aprobación real del environment de GitHub y firma la solicitud de despliegue.
4. Compara el manifiesto previo con GitOps y escribe imagen/chart por digest, values por commit,
   solicitud firmada y hashes de ambos registros. Un cambio concurrente del servicio exige
   revisar el predecesor. Los cambios de otros servicios se conservan al reintentar el push.
5. Espera a que Argo CD confirme esos dos sources, sus revisiones y la imagen esperada como
   `Synced` y `Healthy`. Un despliegue anterior sano no es suficiente.
6. Firma el resultado y lo añade a la auditoría de GitOps con otro commit, conservando los
   artefactos y la solicitud inicial. El registro también se retiene en Actions.

La promoción conserva los digests del build. Un rollback invoca el mismo contrato con un build
anterior verificado, el hash que está desplegado actualmente y los values autorizados.
Ni el deploy ni el rollback ejecutan Maven, Jib, `docker build` o `helm package`.

## Configuración de la plataforma

| Ámbito | Credenciales y configuración |
|---|---|
| Build | `NEXUS_READ_USERNAME/PASSWORD`; en servicios, `BUILD_REGISTRY` y `REGISTRY_BUILD_USERNAME/PASSWORD`, con escritura solo en el almacén de builds |
| Deploy Maven | `NEXUS_PUBLISH_USERNAME/PASSWORD`, con escritura solo en el destino |
| Deploy servicio | `REGISTRY_READ_USERNAME/PASSWORD`, solo lectura; `GITOPS_APP_ID/PRIVATE_KEY`, limitados al repositorio GitOps; `ARGOCD_AUTH_TOKEN`, solo lectura |
| Lectura de builds | `BUILD_READ_TOKEN` o `CANDIDATE_READ_TOKEN` cuando el token de GitHub no accede al repositorio productor |
| Variables de deploy | `GITOPS_REPOSITORY` (nombre dentro de la organización) y `ARGOCD_SERVER` (hostname HTTPS, sin esquema) |
| Autorización | Environments `dev`, `qa`, `prod` con revisión registrada; verificación de release inmutable de workflows |

El ApplicationSet requiere el soporte OCI nativo de Argo CD (3.1 o posterior). La referencia usa
`oci://` con digest para el chart y otro source Git con SHA para los values. Configurar el prefijo
exacto del registro autorizado tanto en AppProject como en Kyverno. Las referencias usan
`ghcr.io/axiz` como ejemplo; no ampliar la lista a registros arbitrarios. Kyverno comprueba la
identidad keyless exacta de `service-build@v2.0.0`, Rekor y el builder SLSA. Al adoptar otra versión,
actualizar la identidad de la política junto con los consumidores.

Referencias: [OCI en Argo CD](https://argo-cd.readthedocs.io/en/stable/user-guide/oci/),
[sources múltiples](https://argo-cd.readthedocs.io/en/stable/user-guide/multiple_sources/),
[firmas Sigstore en Kyverno](https://kyverno.io/docs/policy-types/cluster-policy/verify-images/sigstore/).

## Comprobaciones locales

Los tests de `actions/build-manifest` incluyen corrupción, aislamiento, promoción HTTP local,
auditoría y concurrencia con un repositorio Git bare real. `integration_service_registry.py`
prueba Docker buildx y Helm contra un registro efímero en loopback: imagen amd64, índice
amd64/arm64, chart y recuperación por digest. Esa prueba usa imágenes de fixture sin aplicación;
no acredita la aceptación JVM/native de los servicios, que se valida por separado.

`check_pipeline_contracts.py` verifica la separación y las credenciales de ambos pares. Actionlint
valida los workflows y kubeconform valida las referencias GitOps. Estas pruebas locales no
equivalen a una promoción real en Nexus ni a una prueba de admisión en un clúster.

## Deploy automático independiente de servicios

El `deploy.yaml` generado conserva el modo manual y agrega `workflow_run` al terminar `ci`.
La promoción automática apunta a `dev`; QA, producción y rollback conservan la selección manual
explícita de ejecución, intento, hash, valores y predecesor. El build continúa sin invocar deploy.

`service-build` publica, además del bundle completo, un **comprobante auxiliar firmado** llamado
`service-build-proof-<service>-<attempt>`. No es un tercer tipo de manifiesto de build: enlaza el
hash externo del manifiesto con el repositorio, commit, ejecución, intento, workflow inmutable,
servicio y módulo. La promoción automática autentica este comprobante antes de interpretar su
contenido; después usa el mismo `consume-build` que el modo manual, con todas sus verificaciones
de firma, bytes, gates y ejecución completa exitosa. No obtiene autorización de un checksum
adjunto por el propio artefacto.

El resolver no hace checkout ni ejecuta código del productor. Relee la identidad de la ejecución
en Actions, exige el intento actual completo y exitoso, rechaza forks y PRs, verifica el workflow
`ci.yaml` por su ruta e ID y exige que el commit coincida con el HEAD actual de `main` protegido.
Acepta builds de esa rama o de un tag final `vX.Y.Z`; una candidata, rama alternativa o ejecución
antigua requiere promoción manual. Un CI sin build de producto no tiene comprobante y no inicia
deploy. Los ZIPs del comprobante se limitan antes de extraerlos: dos archivos concretos, sin
entradas adicionales, enlaces ni expansión excesiva.

La resolución usa `BUILD_READ_TOKEN` (o el token de Actions con lectura) y el nuevo secreto de
repositorio `GITOPS_READ_TOKEN`, limitado a **Contents: read** en el repositorio GitOps. Este último
permite observar el predecesor en un commit inmutable antes de solicitar la promoción; el GitHub
App con escritura sólo se utiliza en el deploy protegido. `GITOPS_REPOSITORY` identifica el nombre
del repositorio GitOps dentro de la organización; `GITOPS_BRANCH` es opcional y vale `main` por
defecto. El deploy compara el destino efectivo del ambiente con el observado por el resolver
(`expected-gitops-repository`) y rechaza un override que cambie de repositorio.

La lectura de predecesor distingue un archivo ausente de un fallo de permisos/API, rechaza enlaces
y árboles truncados y comprueba servicio, ambiente y hash. La escritura vuelve a comprobar ese
predecesor mediante el CAS existente: si otro deploy lo cambió durante la espera, la promoción
falla en lugar de sobrescribirlo. `values-revision` se fija al commit autenticado del build, nunca
al commit de la ejecución de deploy. Se mantienen las protecciones y revisiones de cada ambiente.

La validación local cubre los algoritmos con archivos y ZIPs reales y respuestas API controladas;
las firmas de estas pruebas se simulan. La aceptación de OIDC, permisos reales y despliegue en Argo
se realiza con el release inmutable publicado y los repositorios/ambientes configurados.
