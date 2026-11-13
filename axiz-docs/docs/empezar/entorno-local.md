# Entorno local

Cada servicio trae su `compose.yaml` con solo las dependencias que sus capacidades necesitan, y un
perfil `local` que apunta a ellas. El entorno operativo se declara con
`AXIZ_CONFIG_ENVIRONMENT=local` (activar un perfil no concede excepciones de seguridad). La
configuración procede de `application.yaml`, `application-local.yaml`, el entorno y archivos
montados importados mediante `file:`. No se utiliza el esquema `kubernetes:`.

```bash
local/dev-secrets.sh        # crea secretos aleatorios de desarrollo en local/secrets (ignorado por git)
docker compose up -d        # o axiz local up (espera a que todo esté sano)
AXIZ_CONFIG_ENVIRONMENT=local ./mvnw spring-boot:run -Dspring-boot.run.profiles=local
axiz local down -v          # baja todo y borra los volúmenes
```

| Contenedor | Cuándo aparece | Puerto | Para qué |
|---|---|---|---|
| `otel-lgtm` | siempre | 3000, 4317, 4318 | Grafana con Prometheus, Tempo y Loki; recibe OTLP |
| `postgres` | persistencia `aurora`, outbox, inbox o saga sobre JDBC | 5432 | Aurora PostgreSQL local; Flyway crea el esquema |
| `kafka` | `messaging-kafka`, `audit-kafka` | 9092 | MSK local (sin IAM) |
| `redis` | `cache-redis`, `idempotency-redis`, `lock-redis`, rate limit | 6379 | ElastiCache (Valkey) |
| `localstack` | DynamoDB, SQS, SNS, S3, KMS, Secrets Manager, Parameter Store | 4566 | servicios AWS locales; `local/localstack/init.sh` crea tablas, colas y buckets |
| `keycloak` | `security` | 8180 | emisor OIDC local (realm `axiz`) |
| `wiremock` | `http-client`, `soap` | 8089 | dependencias externas simuladas (`local/wiremock/mappings`) |

## Credenciales locales

- Los secretos de desarrollo los genera `local/dev-secrets.sh`: valores aleatorios por clon, nunca
  compartidos ni versionados. El perfil `local` los lee de `local/secrets` con el mismo
  `SecretResolver` que en Kubernetes lee `/var/run/secrets/axiz`.
- Los únicos valores fijos son marcadores evidentes de uso local (`local-only`, y las credenciales
  `test`/`test` de LocalStack, que no son reales). El `.gitleaks.toml` del proyecto solo los permite
  a ellos.

Un token de prueba desde Keycloak:

```bash
curl -s -d grant_type=client_credentials -d client_id=local-client -d client_secret=local-only \
  http://localhost:8180/realms/axiz/protocol/openid-connect/token | jq -r .access_token
```

## Variables útiles

| Variable | Uso |
|---|---|
| `AWS_ACCESS_KEY_ID=test AWS_SECRET_ACCESS_KEY=test AWS_REGION=us-east-1` | SDK de AWS contra LocalStack |
| `OTEL_EXPORTER_OTLP_TRACES_ENDPOINT` | otro colector OTLP |
| `AXIZ_TRACE_SAMPLING` | muestreo de trazas (1.0 en local) |

## Pruebas con contenedores

La prueba de arranque del servicio (`<Servicio>ApplicationTest`) levanta las mismas dependencias
con Testcontainers: importa `PostgresTestConfiguration`, `KafkaTestConfiguration`,
`RedisTestConfiguration` o `LocalStackTestConfiguration` de `axiz-test-spring-boot-starter`
(contenedores con `@ServiceConnection`), así que no necesita el `compose.yaml` corriendo. En
servicios reactivos `BlockHoundExtension` hace fallar la prueba ante cualquier llamada bloqueante en
un hilo del event loop.

## Kubernetes local

El demo incluye un ejemplo completo con **kind**: imágenes con Jib, dependencias dentro del
clúster y los charts reales de cada servicio (`kind/up.sh` en `axiz-demo`).
