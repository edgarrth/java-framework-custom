# Anti-patrones prohibidos

Estas reglas salen de los defectos encontrados al revisar los frameworks que axiz reemplaza; ninguna
puede reaparecer y cada una tiene su verificación.

| Prohibido | En su lugar | Cómo se verifica |
|---|---|---|
| Secretos, llaves o tokens en el repositorio o en `application.yaml` | AWS Secrets Manager + External Secrets, referencias `*-ref` | gitleaks (pre-commit y PR), Semgrep `axiz-hardcoded-secret`, `axiz doctor` |
| Llaves de AWS de larga duración en pipelines o pods | OIDC en GitHub Actions; IRSA o EKS Pod Identity | workflows compartidos, revisión |
| Obtener el JWKS a partir del `iss` del token sin lista blanca; `TestingAuthenticationToken`; todos los usuarios con el mismo rol | resource server con issuers, audiencias y authorities reales | pruebas del starter de seguridad, ArchUnit, Semgrep |
| Registrar `Authorization`, `Cookie`, payloads o retornos sin enmascarar | lista blanca de headers y masking por `@Sensitive` | pruebas del starter de logging |
| Estado mutable en beans singleton (campos de respuesta, headers, clientes creados por llamada) | variables locales, beans inmutables, clientes como beans | ArchUnit (campos `final`) |
| `Hooks.onEachOperator` u otros hooks globales de Reactor; `block()` en código reactivo | Micrometer context-propagation; composición no bloqueante | ArchUnit, Semgrep, BlockHound |
| Java agent de OpenTelemetry junto con Micrometer Tracing | instrumentación por librería y spans del framework | enforcer (`io.opentelemetry.javaagent` prohibido) |
| Tags de métricas con ids, montos o texto libre | tags de catálogos cerrados | `EventMetricsMeterFilter`, límites de cardinalidad |
| Retrofit, Feign u otros clientes HTTP paralelos | `@HttpExchange` de Spring | enforcer y ArchUnit |
| Auto-configuración por `spring.factories`; escaneo de paquetes desde starters | `AutoConfiguration.imports` | pruebas de contexto de cada starter, revisión |
| Beans de Boot reemplazados sin `@ConditionalOnMissingBean` (`ObjectMapper`, `WebClient.Builder`) | customizers | pruebas de contexto |
| `@SpringBootApplication`, `main()` o `application.yaml` dentro de una librería | solo en servicios | arquetipos `library` y `starter`, revisión |
| Lógica o datos de un cliente, banco o producto concreto dentro del framework | configuración o servicio propio | guardia de nombres, revisión |
| AES-ECB, CBC sin MAC, IV fijo, RSA PKCS#1 v1.5 | AES-GCM, RSA-OAEP, KMS | Semgrep `axiz-weak-cipher-mode`, `axiz-fixed-iv` |
| Descifrar mutando entidades gestionadas por JPA | `AttributeConverter` (`EncryptedConverter`) | pruebas del starter |
| Id de deduplicación FIFO aleatorio; envíos sin manejo del resultado | id del evento; confirmación o error visible | Semgrep `axiz-random-fifo-dedup-id`, pruebas del starter |
| `KEYS` en Redis; TTL sobre una llave distinta a la escrita | `SCAN`; TTL sobre la llave real | Semgrep `axiz-redis-keys-command`, pruebas |
| Dependencias milestone o incubadoras en releases; AWS SDK v1 | versiones GA del BOM | enforcer |
| Librerías de test en scope compile | scope test | enforcer |
| Pruebas que afirman un comportamiento defectuoso para subir cobertura | pruebas del comportamiento esperado + mutation testing | PIT, revisión |
| Prefijos de propiedades mezclados | solo `axiz.<capacidad>.*` | metadata de propiedades, revisión |
| Rutas de archivos construidas con datos del usuario | directorios temporales aleatorios, `ObjectKey` | Semgrep `axiz-path-from-user-input` |
| `helm upgrade` desde el pipeline contra el clúster | GitOps con Argo CD | workflows compartidos (`service-cd`) |
