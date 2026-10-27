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
  se guardan fuera de la sesión. Sin esa autorización, `POST /v1/projects/github` responde 403
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
java -jar axiz-initializr/target/axiz-initializr-1.0.0.jar --spring.profiles.active=local
```

Abre <http://localhost:8090> e inicia sesión con `ana` / `local-only-ana`. El perfil `local` usa
el puerto 8090 y un secreto de cliente `local-only`. Para el botón de GitHub hace falta una OAuth
App propia con el callback `http://localhost:8090/authorized/github` (`AXIZ_GITHUB_CLIENT_ID`,
`AXIZ_GITHUB_CLIENT_SECRET`).
