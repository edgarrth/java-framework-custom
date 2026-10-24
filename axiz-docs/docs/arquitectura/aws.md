# Infraestructura AWS

axiz integra solo los servicios aprobados. Cada uno tiene un módulo dueño y un equivalente local
para desarrollo y pruebas. Cualquier otro (GCP, Hazelcast, Vault, EventBridge, Cognito, API
Gateway, CodeArtifact, ECR) queda fuera, y el enforcer bloquea sus librerías.

| Servicio | Uso | Módulo axiz | Local / pruebas |
|---|---|---|---|
| Amazon EKS | cómputo de todos los servicios | chart Helm generado | kind |
| Aurora PostgreSQL | base relacional, outbox, inbox, sagas, secuencias | `axiz-core-persistence-jpa` | PostgreSQL (Compose, Testcontainers) |
| Amazon DynamoDB | NoSQL, inbox, idempotencia, locks, secuencias | `axiz-core-persistence-dynamodb` | LocalStack |
| Amazon ElastiCache (Redis/Valkey) | caché L2, idempotencia, rate limit, locks | `axiz-core-cache-redis` | Valkey (Compose, Testcontainers) |
| Amazon MSK | eventos (Kafka con autenticación IAM), auditoría | `axiz-core-messaging-kafka` | Kafka (Compose, Testcontainers) |
| Amazon SQS | colas estándar y FIFO, DLQ | `axiz-core-messaging-sqs` | LocalStack |
| Amazon SNS | pub/sub estándar y FIFO | `axiz-core-messaging-sns` | LocalStack |
| AWS Secrets Manager | secretos (sincronizados con External Secrets) | `axiz-core-secrets-aws` | LocalStack, `local/secrets` |
| AWS Systems Manager Parameter Store | parámetros compartidos fuera del clúster | `axiz-config-spring-boot-starter` | LocalStack |
| ConfigMaps montados como archivos | configuración base de cada servicio | `axiz-config-spring-boot-starter` | perfil `local`, kind |
| AWS KMS | cifrado de campos (envelope) y de objetos en S3 | `axiz-core-crypto-kms` | LocalStack |
| Amazon S3 | archivos, batch, archivo de auditoría | `axiz-core-storage-s3` | LocalStack |
| Observabilidad | OpenTelemetry Collector en EKS; Prometheus y Grafana; trazas y logs en el backend OTLP de plataforma | `axiz-observability-*`, `axiz-metrics-*` | Grafana LGTM en Docker |

Fuera de AWS: Nexus Repository (artefactos Maven e imágenes), GitHub Actions (CI/CD), Argo CD
(despliegue) y el IdP OIDC corporativo.

## Convenciones de los clientes AWS

- Un solo SDK: AWS SDK v2 (el v1 está prohibido). Todo cliente que crea el framework (SQS, SNS, S3,
  DynamoDB, KMS, Secrets Manager, Parameter Store) se construye con `AwsClients`, que agrega el
  interceptor de observación (un span CLIENT por llamada con servicio y operación), la región de
  `axiz.aws.region` y el endpoint de `axiz.aws.<servicio>.endpoint` o, si no está,
  `axiz.aws.endpoint` (LocalStack en local). Claves de servicio: `sqs`, `sns`, `s3`, `dynamodb`,
  `kms`, `secretsmanager`, `ssm`. Un bean propio del cliente reemplaza al del framework.
  El módulo adaptador (`axiz-core-*-sqs`, `-dynamodb`, `-kms`, `-aws`, `-s3`, `-sns`) trae
  `axiz-core-observability-awssdk`, así el cliente nunca se crea sin esa configuración.
- Credenciales siempre por la cadena por defecto: IRSA o EKS Pod Identity en el clúster, variables
  `test`/`test` contra LocalStack. Nunca llaves en propiedades.
- Nombres de tablas, colas y tópicos por convención con prefijo de ambiente
  (`${axiz.dynamodb.table-prefix}axiz-idempotency`), creados por Terraform (`infra.yaml`), nunca por
  el servicio al arrancar.
- MSK con IAM (`axiz.messaging.kafka.iam=true` en los ambientes), SQS FIFO con el id del evento como
  id de deduplicación, S3 con SSE-KMS y URLs prefirmadas con TTL máximo.
