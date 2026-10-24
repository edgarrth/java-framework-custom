# Verificación de esta entrega

## review27 (tres auditorías sobre review26)

La auditoría del 17 de octubre ejecutó el demo con Maven real: 117 de 118 pruebas (fallaba el
arranque de `payment-process` sin región de AWS) y 88 de 88 comprobaciones de escenarios. Lo
corregido y lo verificado aquí, sin Maven ni Docker:

| Qué | Resultado aquí |
|---|---|
| Compilación de main y pruebas de los 8 servicios | OK, 0 fallas |
| `payment-process` sin región de AWS | Secrets Manager de Spring Cloud AWS queda apagado por defecto (`axiz-config`); el servicio lo activa solo donde lo usa |
| Llaves de prueba | `payments-business` y `payments-history` ya no versionan llaves: `EphemeralSecrets` las genera en cada ejecución (`@DynamicPropertySource`) |
| `core-integration` | WS-Security con `password-mode: LEGACY_SHA1_DIGEST` explícito (lo exige el core simulado); `allow-plain-http` solo en el perfil local y en kind |
| `payments-history` | versión 0.1.0, igual que el resto |
| Charts de los 8 servicios (dev, qa, prod, kind) | kubeconform estricto y kube-linter sin hallazgos; ConfigMaps montados como archivos, sin token de ServiceAccount ni RBAC, salida restringida |
| Pruebas con contexto de Spring, `*IT` con Testcontainers, escenarios | solo con Maven y Docker (sección 4 del README) |

## review26 (auditoría de código sobre review24)

La auditoría ejecutó el demo con Maven real: 115 pruebas, 110 aprobadas, 1 fallo y 4 errores, y 86
de 87 comprobaciones de escenarios. Lo corregido y lo verificado aquí, sin Maven ni Docker:

| Qué | Resultado aquí |
|---|---|
| Compilación de main y pruebas de los 8 servicios | OK (modelos de OpenAPI reemplazados por stubs generados del contrato) |
| `settlement-batch`, pruebas unitarias | 12 OK, entre ellas la nueva: una nueva ejecución de un archivo ya cargado escribe su resumen si falta, y no lo reescribe si existe |
| `payments-history`, pruebas unitarias | 12 OK |
| `PaymentsHistoryApplicationTest` y `SettlementBatchApplicationTest` (Testcontainers) | compilan; corren con Docker (`./mvnw verify`). El historial consulta con un cliente autenticado (la política de acceso respondía 404 a un llamador anónimo); el batch arranca con `settlement.run-on-startup=false`, carga con `SettlementRunner.load` un archivo propio de la prueba y comprueba filas, resumen, idempotencia y resumen reescrito |
| `scripts/scenarios.sh` | shellcheck sin hallazgos; contra el simulador: 88 comprobaciones en verde (una nueva: resumen perdido y reescrito). Con fallas inyectadas, 82 en verde y 6 `FAIL` con su motivo, código de salida 1 |
| `scripts/demo.sh` | shellcheck sin hallazgos; `published_by` probado con salidas de `docker ps` reales: rangos (`0.0.0.0:4317-4318->4317-4318/tcp`), IPv6 y puertos expuestos sin publicar; la infraestructura del demo se reconoce por la etiqueta del proyecto de Compose `axiz-demo` |
| Realm de Keycloak | JSON válido; el cliente `axiz-initializr` emite tokens con audiencia `axiz-initializr` |

No ejecutado aquí: las pruebas de arranque con Testcontainers, `DailyLimitConcurrencyIT`, el entorno
local completo, el smoke y los escenarios contra los servicios reales (`scripts/demo.sh up --build`,
`smoke`, `scenarios`).

## Entregas anteriores

Sin acceso a Maven Central ni Docker en el entorno de construcción; `./mvnw verify`, las pruebas con
contenedores, el entorno local y el e2e en kind corren en CI (`ci.yaml`, `e2e.yaml`).

| Qué | Resultado local |
|---|---|
| Compilación (main y pruebas) de los 8 servicios | OK |
| Pruebas unitarias + `ArchitectureTest` | 94 OK: payments-business 15, beneficiaries-crud 15, payments-experience 10, core-integration 13, payment-process 12, notifications-processor 6, settlement-batch 11, payments-history 12 |
| Nombres de bean (`UNIQUE_BEAN_NAMES`) | OK en los 8 servicios, con las anotaciones de Spring resueltas; con la clase `SettlementJob` de la entrega anterior la regla marca `settlementJob` (el error de arranque de `settlement-batch`) |
| Configuración de Spring Boot | ninguna propiedad eliminada o deprecada en Boot 4.1.1 (comparado con sus metadatos); `management.otlp.tracing.endpoint` pasó a `management.opentelemetry.tracing.export.otlp.endpoint` en los servicios y en kind |
| Cobertura de capacidades (`coverage/`) | 2 OK: todas las capacidades del catálogo están usadas y su evidencia existe |
| Charts (8 servicios × dev/qa/prod) | kubeconform estricto y kube-linter sin hallazgos |
| Workflows y scripts | actionlint + shellcheck sin hallazgos |
| `kind/deps` | manifiestos válidos (kubeconform) |
| Migraciones y SQL sobre PostgreSQL 16 real | las migraciones de los 4 servicios con base de datos se aplican en orden sin errores; las columnas de `PaymentOrderEntity` coinciden con el tipo que Hibernate valida (encontró `currency CHAR(3)`, corregido con `V4__currency_varchar.sql`); las sentencias SQL de outbox, secuencias, sagas e inbox R2DBC del framework y las consultas nativas del demo se preparan (`PREPARE`) contra esos esquemas sin errores. Las migraciones de los 10 proyectos generados con Aurora también se aplican sin errores |
| Infraestructura local | Kafka con listener interno (`kafka:29092`) y externo (`localhost:9092`); `demo.sh up` pregunta al broker por `payments`, `payments.dlq` y `audit` hasta 120 s, sin depender de `docker compose wait` (que falló con los topics ya creados en una versión de Compose); probado con topics presentes, topics que aparecen tarde y job fallido |
| `scripts/demo.sh` y `scripts/smoke.sh` | shellcheck sin hallazgos; `up` en una copia nueva sin `target/` (antes terminaba sin mensaje) compila y arranca; ninguna falla inesperada termina en silencio (`trap ERR`, probado con las salidas normales de error sin mensajes de más); `wait` y `smoke` fallan de inmediato con servicios sin arrancar; `up` recompila con `clean package` (un `package` incremental dejó en el jar de `settlement-batch` la clase `SettlementJob` ya renombrada) y también detecta fuentes borrados o renombrados; ejecutados contra servicios simulados: flujo completo OK, fallos de token, de pago y de servicios caídos con mensaje y código de salida 1, puerto ocupado por un nodo de kind detectado, servicio que se cae al arrancar detectado con el final de su log; pago que queda en `RECEIVED` con el grupo de consumidores, los offsets del topic y el aviso si payment-process no creó consumidor |
| `scripts/scenarios.sh` | shellcheck sin hallazgos; ejecutado completo contra un simulador con estado de los 7 servicios, Keycloak, WireMock, Grafana, Kafka, PostgreSQL y LocalStack: 87 verificaciones en verde (el simulador de LocalStack responde `list-objects-v2` como el AWS CLI real, que pagina y devuelve `None` en `KeyCount`: con la consulta anterior falla `audit ... archived on S3`, el error de la ejecución real; con `length(Contents)` pasa), y con fallas inyectadas (límite diario sin liberar, débito sin reversa, crédito sin movimiento, traza cortada, DLQ incompleta) cada una aparece como `FAIL` con su motivo y el resumen termina con código 1. Cada valor esperado (códigos de error, estados, campos, rutas, tablas) se tomó del código de los servicios y del framework; el simulador no reemplaza la ejecución real, que hace `scripts/demo.sh scenarios` |
| Flujo de eventos del pago | revisado de punta a punta contra la configuración: tipos publicados por payments-business y consumidos por payment-process, payments-history y notifications-processor; tipos sin handler en el topic compartido confirmados (`ignore-unknown-types`); scopes y audiencias de Keycloak para las llamadas de la saga; rutas de core-integration contra los stubs de WireMock con la cuenta sin enmascarar |

Omitido localmente: `DailyLimitConcurrencyIT` (concurrencia real sobre PostgreSQL), pruebas de arranque (`*ApplicationTest`, Testcontainers; la de payments-history
recorre Kafka → inbox R2DBC → bus reactivo → PostgreSQL), `ContractExamplesTest`,
la colección de Bruno y el smoke test, que necesitan los servicios corriendo.
