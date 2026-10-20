# Verificación de esta entrega

Sin acceso a Maven Central en el entorno de construcción; `mvn verify` corre en CI.

| Módulo | Resultado local |
|---|---|
| `axiz-cli` | compila; 17 pruebas OK (new, add, generate, contract sync/lint/diff, upgrade, doctor, local, catalog) contra el motor real del framework |
| `axiz-initializr` | compila código y pruebas; las pruebas web con contexto de Spring (MockMvc, seguridad, publicación en GitHub) corren en CI. La cadena de seguridad se ejecutó aparte con Spring Security 7.1 y Spring 7.0 compilados desde fuentes (MockMvc, contexto web real): el navegador sin sesión va directo al SSO (sin página para elegir proveedor); la autorización de GitHub usa su propio callback (`/authorized/github`); un código de GitHub enviado al callback de login no inicia sesión y el servicio de usuario rechaza cualquier registro que no sea `sso`; sin autorización de GitHub la API responde 403 con `authorize` en vez de redirigir; un POST de una sesión del navegador sin token CSRF se rechaza y con el `XSRF-TOKEN` de la página pasa. Antes, con dos registros OAuth, el login aceptaba GitHub como proveedor de identidad y el botón de GitHub no funcionaba desde la UI |
| Initializr en local | perfil `local` (puerto 8090) y cliente `axiz-initializr` en el realm del demo (`axiz-demo/local/keycloak/axiz-realm.json`); no se pudo arrancar aquí (sin Docker ni Keycloak), se confirma con los pasos de la sección 10.1 del README |
| Audiencia del API del initializr (review26) | `SecurityConfigurationTest` ejecutado con Spring Security 7.1 compilado desde fuentes y Nimbus real: 3 OK. Un token firmado del emisor con audiencia `axiz-initializr` pasa; con audiencia de otra API, sin audiencia o de otro emisor se rechaza (`JwtValidationException`, 401 en la cadena). El resto de pruebas web corre en CI |
| `axiz-rewrite-recipes` | omitido: necesita `rewrite-migrate-java` y `rewrite-spring` |
| Chart del initializr | kubeconform estricto y kube-linter sin hallazgos en dev, qa y prod |
| Workflows | actionlint + shellcheck sin hallazgos |

La salida de `axiz <comando> --help` usada en `axiz-docs` se obtuvo de esta misma CLI.
