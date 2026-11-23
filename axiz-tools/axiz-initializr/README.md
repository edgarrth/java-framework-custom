# axiz-initializr

Generador web y API REST de la plataforma axiz, detrás del SSO corporativo. La interfaz y la API
(`/v1/metadata`, `/v1/projects`, `/v1/projects/validate`, `/v1/contracts/lint`,
`/v1/projects/github`) generan servicios con el mismo motor que la CLI (`pe.axiz:axiz-codegen`).

- Contrato: `docs/openapi.yaml`.
- Seguridad: login OIDC para la interfaz (registro `sso`, el único que inicia sesión) y JWT bearer
  para la CLI y las automatizaciones; los repositorios de GitHub se crean con la autorización OAuth del
  propio usuario (registro `github`, callback `/authorized/github`). Un token de GitHub se mantiene solo
  en la sesión HTTP autenticada durante un máximo de cinco minutos, se elimina tras la validación local
  y antes de cada intento de publicación remota (éxito o error remoto) y se pierde al cerrar sesión o al
  expirar la sesión. Los clientes de acceso y refresco del SSO no se conservan. Sin esa autorización,
  `POST /v1/projects/github` responde 403 con la URL `authorize`; la API nunca redirige. Los tokens
  bearer deben provenir del emisor del SSO y llevar la audiencia `axiz.initializr.audience`
  (`axiz-initializr` por defecto, `AXIZ_INITIALIZR_AUDIENCE`): un token del mismo proveedor de identidad
  emitido para otra API recibe 401. En el proveedor de identidad, el cliente que usa la CLI necesita un
  mapper de audiencia con ese valor.
- La generación ocurre en memoria con límites de tamaño; cada generación se audita y se contabiliza
  (`axiz.initializr.generations`).
- Despliegue: chart en `deploy/helm`, configuración en ConfigMaps y secretos de cliente desde el
  ExternalSecret.

Ejecución local contra el Keycloak de la demo (`axiz-demo`: `scripts/demo.sh up`; realm `axiz`,
cliente `axiz-initializr`), en el puerto 8090:

```bash
./mvnw -pl axiz-initializr -am clean package -DskipTests
java -jar axiz-initializr/target/axiz-initializr-2.0.0.jar --spring.profiles.active=local
# http://localhost:8090, user ana / local-only-ana
```

Crear repositorios de GitHub también requiere una GitHub OAuth App con el callback
`http://localhost:8090/authorized/github` (`AXIZ_GITHUB_CLIENT_ID`, `AXIZ_GITHUB_CLIENT_SECRET` y,
de forma opcional, `AXIZ_GITHUB_ORGANIZATION`). El proyecto se sube como un único commit, `feat: generate <name> with axiz
initializr`, un Conventional Commit que abre el changelog; la primera release es `0.1.0`, el
`initial-version` del `release-please-config.json` generado.

## Publicación y recuperación

Con `POST /v1/projects/github` se envía un UUID en `Idempotency-Key`. El mismo sujeto autenticado,
operación, repositorio y proyecto generado exacto pueden reanudar una publicación parcial. Se mantienen
la misma petición y la misma versión del framework entre reintentos; una salida distinta falla en modo
cerrado con 409. La interfaz conserva esa operación y la petición en el almacenamiento de sesión de la
pestaña hasta el éxito. Contiene el texto del proyecto y del contrato, nunca tokens OAuth: no se ponen
datos de producción ni secretos en las peticiones de generación.

La autorización de GitHub requiere `repo` y `workflow`; el publicador verifica los scopes concedidos
antes de cualquier escritura, porque los proyectos generados incluyen workflows. Se vuelve a autorizar
después de un intento fallido o de una concesión consumida o expirada. Esto elimina las credenciales
locales; no revoca la concesión OAuth en el proveedor, que el usuario puede revocar en la configuración
de GitHub.

Al arrancar se requiere un secreto de despliegue compartido `axiz.initializr.publication-key` (o
`AXIZ_INITIALIZR_PUBLICATION_KEY`): una clave aleatoria codificada en Base64 de al menos 32 bytes,
montada mediante el almacén de secretos, nunca en un ConfigMap. Todas las réplicas necesitan la misma
clave. Un marcador HMAC en la descripción del repositorio privado liga la operación, el sujeto, el
repositorio y el digest del proyecto. No se elimina mientras se necesite recuperación; rotar la clave
requiere drenar o conciliar las operaciones pendientes. No es una credencial de GitHub. Aquí las
descripciones de GitHub se limitan a 160 caracteres para dejar espacio al marcador (la generación de
ZIP acepta 300 caracteres).

201 confirma la rama. Un 503 incluye `operationId`, `phase` y `repository` para reintentar con la misma
operación después de autorizar. Un 409 indica un conflicto de identidad o de contenido y requiere
inspección. La recuperación verifica el marcador y la ascendencia, y nunca hace force-push, ni
sobrescribe el commit de otro usuario, ni elimina un repositorio. Los acuses perdidos están cubiertos
en las etapas de creación, árbol, commit y ref. El proyecto generado es determinista solo para la misma
versión del generador y las mismas entradas.

## Sesiones y cuotas

La aplicación usa sesiones servlet locales al proceso. Se configura afinidad de navegador en el ingress
cuando apunta directamente a los pods; el chart usa afinidad ClientIP para el tráfico que pasa por su
Service. Un reinicio del pod exige a propósito volver a autorizar SSO y GitHub. No se activa un almacén
de sesiones replicado sin implementar también un consumo atómico de un solo uso entre réplicas.
La API bearer no tiene estado; la publicación en GitHub requiere en concreto la concesión de la sesión
del navegador.

Las cuotas en Redis usan `axiz-security-redis-spring-boot-starter`, compartido por todas las réplicas,
con el namespace fijo `axiz:initializr:quota:v1:`. `spring.data.redis.host/port/username/password` se
configuran mediante el límite normal de configuración y secretos. Fuera de local/test/CI se exigen TLS
y la verificación del nombre de host. Los timeouts de conexión y de comando son de un segundo por
defecto. La ejecución local también necesita Redis/Valkey (la demo lo aporta); el texto plano en local
se rige por la política de ambiente local del framework.

`axiz.initializr.limits` usa por defecto una ventana de un minuto, 10 generaciones, 2 intentos de
publicación y 20 validaciones por sujeto autenticado, y 4 peticiones costosas concurrentes por réplica.
Las cabeceras de identidad del cliente no pueden cambiar ese sujeto. Un 429 incluye `Retry-After`; los
errores de Redis niegan el trabajo en lugar de permitirlo. El limitador compartido puede informar una
caída como una cuota denegada. En producción no se configura ninguna alternativa local al proceso. Los
presupuestos se ajustan a los recursos desplegados.

## Verificación

Se ejecuta `mvn -Pquality -pl axiz-initializr -am test` en `axiz-tools` después de instalar el framework.
Las pruebas cubren la comprobación previa de scopes, las respuestas perdidas fase por fase, el rechazo
de conflictos y repeticiones, la expiración, el consumo y el cierre de sesión, los errores enmascarados,
las cuotas por sujeto y la concurrencia acotada de generación. Las pruebas de GitHub usan un fake HTTP
con estado y no crean repositorios reales.

## Transporte y auditoría de la publicación

Se usa una GitHub OAuth App clásica con las concesiones `repo` y `workflow`. Este flujo de navegador no
admite tokens sin la cabecera de scopes de OAuth (incluidos los tokens de instalación de una GitHub
App). La configuración confiable del despliegue selecciona el origen HTTPS de la API (`github-api` para
Enterprise); la entrada de la petición no puede sobrescribirlo. En esa URL se rechazan credenciales,
query y fragmento. El cliente dedicado nunca sigue redirecciones, se conecta en un máximo de 3 segundos
y limita cada petición a 10 segundos. La creación inicial de la rama tiene como máximo tres intentos de
lectura ante 404/409, con backoff de 200/400 ms; las escrituras remotas no se reintentan a ciegas. Un
401/403 en la comprobación previa de scopes solicita una nueva autorización.

La publicación emite eventos de auditoría `project.publish` con un seudónimo SHA-256 del sujeto
autenticado, el repositorio, el ID de operación, la fase y el resultado. Nunca audita el token OAuth ni
el contenido generado. `project.publish.requested` registra la intención aceptada con un resultado de
operación desconocido; solo `CONFIRMED` registra un resultado remoto confirmado. El Initializr usa la
entrega SYNC del framework, sin una cola asíncrona en memoria. Es una entrega síncrona, no un journal
durable ni una transacción atómica con GitHub. El publicador confirmado del framework informa la
entrega no confirmada al destino mediante su alternativa y sus métricas; un `publish()` que retorna no
demuestra almacenamiento durable. Una excepción del publicador antes del trabajo remoto lo aborta,
mientras que una posterior a una escritura remota no puede reemplazar ese resultado de negocio. Se
configura y monitorea un colector o destino persistente y sus métricas de entrega. Los valores por
defecto basados solo en logging no aportan almacenamiento resistente a caídas ni una certificación de
cumplimiento PCI.

Fuera del perfil local, `AXIZ_SSO_ISSUER` es obligatorio. La ejecución local también necesita un
`AXIZ_INITIALIZR_PUBLICATION_KEY` privado y aleatorio de al menos 32 bytes codificado en Base64; el
servicio no arranca sin él. Se obtiene del gestor de secretos local o con `openssl rand -base64 32`,
nunca de una clave versionada. La clave no se regenera mientras haya publicaciones parciales que
necesiten recuperación.

Las cuotas comparan los segmentos MVC decodificados y usan claves de operación canónicas, de modo que
las rutas equivalentes codificadas con porcentaje comparten la misma cuota. El consumo de OAuth usa el
mutex de la sesión servlet entre las fachadas de la petición. Si el almacenamiento de la pestaña no
puede conservar una petición grande de proyecto generado, la interfaz lo informa antes de publicar y
pide reducir el tamaño del contrato o liberar el almacenamiento de la pestaña.

### Rechazos de GitHub y lectura de auditoría

Un 401 exige renovar la autorización delegada. Un 403 de permisos detiene la publicación con
`CONFLICT`; no invita a repetir una operación que la organización prohíbe. Los 403 que informan
`Retry-After` o `X-RateLimit-Remaining: 0`, y los 429, dejan el contenido pendiente para un reintento
posterior autorizado. No se repiten mutaciones automáticamente dentro de la llamada.

La acción `project.publish.requested` con outcome `SUCCESS` significa que se aceptó y auditó la
solicitud, no que GitHub publicó el proyecto: su `operationOutcome` permanece `UNKNOWN`. Sólo
`project.publish` con fase `CONFIRMED` declara el resultado de publicación `COMMITTED`.
El sujeto SHA-256 es un seudónimo estable, no anonimización ni una protección contra diccionarios.
El operador debe conservar un directorio de identidades autorizado y acceso restringido para
correlacionar ese seudónimo con el principal autenticado cuando investigue eventos de auditoría.

### Cuotas de peticiones y concurrencia

La cuota por sujeto y por operación cuenta los intentos autenticados, incluidos los rechazados porque
todos los workers de generación están ocupados. Es un presupuesto de control de abuso, no un contador
de facturación de trabajos aceptados. El semáforo separado acota el trabajo activo en cada réplica. Un
llamador que recibe 429 debe respetar Retry-After en lugar de reintentar libremente mientras el
servicio está saturado.
