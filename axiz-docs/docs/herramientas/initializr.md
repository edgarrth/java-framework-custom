# Initializr

`axiz-initializr` es la versión web del generador, detrás del SSO corporativo, y la API que usan la
CLI en modo online y las automatizaciones.

- **UI** en `/`: datos del proyecto, arquetipo con su rol y las capacidades que ya incluye, estilo,
  stack, persistencia y capacidades extra con sus restricciones, carga y validación del contrato,
  y descarga del ZIP o creación del repositorio en GitHub.
- **API** bajo `/v1` (contrato en `axiz-initializr/docs/openapi.yaml`):

| Operación | Para qué |
|---|---|
| `GET /v1/metadata` | arquetipos, estilos, stacks, persistencias y capacidades de esta versión |
| `POST /v1/projects` | genera el proyecto y devuelve un ZIP |
| `POST /v1/projects/validate` | valida la combinación y el contrato sin generar |
| `POST /v1/contracts/lint` | lint del contrato con las reglas de axiz |
| `POST /v1/projects/github` | crea el repositorio con la autorización OAuth del usuario y sube el proyecto |

## Seguridad

- Login OIDC para la UI (cookie CSRF para la SPA, CSP estricta) y JWT bearer para la CLI y las
  automatizaciones; solo los probes de health son anónimos.
- Solo el registro `sso` inicia sesión: la página va directo al SSO, sin elegir proveedor, y el
  login rechaza cualquier otro registro aunque su código llegue al callback de login.
- Los repositorios de GitHub se crean con la autorización OAuth del propio usuario (registro
  `github`, callback `/authorized/github`), guardada para el usuario ya autenticado; los tokens no
  se guardan fuera de la sesión, caducan a los cinco minutos y se retiran antes de cada intento.
  Los permisos exigidos son `repo` y `workflow`. Sin esa autorización, `POST /v1/projects/github` responde 403
  con la URL `authorize`; la API nunca redirige.
- La generación ocurre **en memoria**: sin directorios temporales ni rutas construidas con datos del
  usuario, con límites de tamaño para contratos y archivos.
- Cada generación se audita y se cuenta (`axiz.initializr.generations` por arquetipo, estilo, stack
  y resultado).

Se despliega con su propio chart (`axiz-initializr/deploy/helm`), con configuración en ConfigMaps y
los secretos de cliente desde el ExternalSecret, por el mismo pipeline GitOps que un servicio.

## Probarlo en local

Con el demo levantado (su Keycloak tiene el cliente `axiz-initializr`):

```bash
cd axiz-tools
./mvnw -pl axiz-initializr -am clean package -DskipTests
java -jar axiz-initializr/target/axiz-initializr-2.0.0.jar --spring.profiles.active=local
```

Se abre <http://localhost:8090> y se inicia sesión con `ana` / `local-only-ana`. El perfil `local` usa
el puerto 8090 y un secreto de cliente `local-only`. Para el botón de GitHub hace falta una OAuth
App propia con el callback `http://localhost:8090/authorized/github` (`AXIZ_GITHUB_CLIENT_ID`,
`AXIZ_GITHUB_CLIENT_SECRET`).

## Publicación recuperable y límites

Enviar `Idempotency-Key` con un UUID y conservarlo junto con la petición original. La UI lo hace
en el almacenamiento de la pestaña; no introducir secretos ni datos productivos en los contratos.
Tras un 503, autorizar GitHub otra vez y repetir esa misma operación. Un 409 detiene la recuperación
porque cambió la propiedad o el contenido. El marcador HMAC de la descripción vincula sujeto,
operación, repositorio y digest del proyecto. Se exige una clave compartida de al menos 256 bits,
`axiz.initializr.publication-key`, montada como secreto. No se fuerza una rama ni se elimina un
repositorio para resolver el conflicto. Mantener la versión del generador mientras hay recuperaciones.

El starter de seguridad Redis aplica cuotas por sujeto autenticado y operación: por defecto,
10 generaciones, 2 publicaciones y 20 validaciones por minuto, además de 4 tareas concurrentes por
réplica. Se configura con `axiz.initializr.limits.*`; una denegación devuelve 429 y `Retry-After`.
Redis es obligatorio, con TLS fuera de entornos locales. Los errores del almacén no habilitan trabajo.

Las sesiones servlet son locales: el ingress debe mantener afinidad si enruta directamente a pods;
el Service del chart configura afinidad ClientIP. Reiniciar el pod exige autenticarse de nuevo.
Las cuotas sí se comparten entre réplicas. Ver los detalles de recuperación, despliegue y pruebas
en el [README de Initializr](https://github.com/edgarrth/java-framework-custom/blob/main/axiz-tools/axiz-initializr/README.md).

### Transporte, cuotas y auditoría de publicación

Las cuotas reconocen los segmentos decodificados de MVC; codificar caracteres de la ruta no crea
una cuota nueva. El cliente GitHub usa HTTPS, no sigue redirecciones y limita conexión a 3 segundos
y respuesta a 10 segundos. La inicialización de la rama permite tres lecturas 404/409 con esperas
acotadas; no repite escrituras inciertas. Se requiere una OAuth App clásica con `repo` y `workflow`.

El servicio exige la clave de publicación al arrancar y, fuera del perfil local, `AXIZ_SSO_ISSUER`.
La clave debe provenir de un almacén de secretos, compartida entre réplicas. El consumo único de
la autorización usa el mutex de sesión aunque las peticiones reciban fachadas distintas.

Los eventos `project.publish.requested` y `project.publish` incluyen sujeto seudonimizado, repositorio,
identificador de operación, fase y resultado, sin token OAuth ni archivos generados. La entrega usa
SYNC, sin cola asíncrona, y se monitoriza; no es una confirmación de persistencia durable ni una
transacción atómica con GitHub. Un fallo posterior a una escritura remota no transforma un éxito confirmado en un fallo del negocio.
La interfaz avisa antes de publicar si no puede conservar la petición en el almacenamiento de la pestaña.

Una revocación 401 de GitHub vuelve a pedir autorización (403 del API local). Un 403 de
permisos de GitHub detiene la operación con 409; un 403 con cabeceras de rate limit o un 429
permite reintentar más tarde con la misma operación y proyecto. El publicador no repite
mutaciones automáticamente. `project.publish.requested` exitoso sólo confirma aceptación
y entrega de esa intención; `operationOutcome` sigue `UNKNOWN` hasta `CONFIRMED`.
El SHA-256 del sujeto es seudonimización, no anonimización: mantenga un directorio de
identidades restringido que permita la correlación autorizada durante una investigación.
