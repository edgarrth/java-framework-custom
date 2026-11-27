# Demo de axiz: pagos

Un pequeño procesador de pagos construido con el framework axiz: nueve servicios, generados con
`axiz new` y ampliados con `axiz add` / `axiz generate`. Los servicios, junto con los ejemplos
ejecutables de `examples/` (variantes de proveedores que los servicios no necesitan: otros almacenes,
brokers y proveedores de claves), usan **todas las capacidades del catálogo de axiz**; `coverage/` lo
comprueba y la [matriz de capacidades](coverage/CAPABILITIES.md) muestra qué servicio o ejemplo
demuestra cada una. Los arquetipos `support`, `library` y `starter` no forman parte de este flujo de
negocio: la CLI y el Initializr los generan y verifican en el build del framework. Flujo de negocio: un
cliente paga a un beneficiario frecuente desde la app web o móvil; el pago se debita de su cuenta en el
core bancario, se envía a la red interbancaria, se notifica, se audita y se concilia con el archivo
diario de liquidación.

Todos los nombres, cuentas y datos son ficticios. Las credenciales locales son marcadores con la marca
`local-only`; los ambientes reales leen los secretos desde AWS Secrets Manager.

## Servicios

| Servicio | Rol / estructura + extensiones / stack | Persistencia | Qué muestra |
|---|---|---|---|
| [payments-ux](services/payments-ux) | ux / bff / reactive | Ninguna | API de canal, composiciones con partes opcionales, relay de tokens, caché e idempotencia en Redis, feature flags, enmascaramiento, traducción de errores |
| [payment-orders](services/payment-orders) | business / hexagonal + cqrs / servlet | Aurora | Reglas de dominio, calendario de negocio y hora de corte, secuencias, cifrado de campos + índice ciego, outbox transaccional, auditoría en MSK |
| [beneficiaries](services/beneficiaries) | business / layered / servlet | DynamoDB | Repositorios con paginación por cursor, borrado lógico, registro atómico, lecturas fuertemente consistentes, idempotencia en DynamoDB |
| [banking-core](services/banking-core) | core / hexagonal / servlet | Ninguna | Capa anticorrupción: REST con client credentials, SOAP con WS-Security, locks en Redis, auditoría en SQS |
| [payment-network-gateway](services/payment-network-gateway) | gateway / hexagonal / servlet | Ninguna | Aislamiento de la red de pagos, API key desde secretos, idempotencia de transferencias con Redis, auditoría en SQS |
| [payment-process](services/payment-process) | business / hexagonal + saga / servlet | Aurora | Saga con compensaciones y recuperación, consumidor Kafka, ShedLock, tenancy |
| [notifications-processor](services/notifications-processor) | event-processor / eda | DynamoDB | Handlers de Kafka y SQS, inbox, notificaciones a SNS, archivo de auditoría en S3 con locks de DynamoDB |
| [settlement-batch](services/settlement-batch) | batch / batch / CronJob | Aurora | Spring Batch sobre un archivo de ancho fijo en S3, totales de control, carga idempotente, códigos de salida del job |
| [payments-history](services/payments-history) | business / hexagonal + cqrs / reactive | Aurora (R2DBC) | Modelo de lectura alimentado por Kafka: buses reactivos de comandos y consultas, transacciones reactivas sobre R2DBC, Spring Data R2DBC con auditoría, columnas cifradas y monetarias, inbox R2DBC |

Los contratos están en [`contracts/`](contracts): el OpenAPI de cada servicio REST, el AsyncAPI de los
eventos y los sistemas externos (simulados) en `contracts/external`.

## Arquitectura

[`docs/architecture.drawio`](docs/architecture.drawio) contiene diagramas de contenedores C4, uno por
flujo (el iniciador a la izquierda y las flechas hacia la derecha):

1. **Pago desde el canal**: app -> payments-ux -> beneficiaries y payment-orders.
2. **Ejecución del pago**: payment-orders -> MSK -> payment-process (saga) -> banking-core -> core bancario; payment-process -> payment-network-gateway -> red de pagos.
3. **Cierre, notificación y auditoría**: los cambios de estado y los eventos de auditoría llegan a notifications-processor (SNS, S3).
4. **Liquidación diaria**: archivo de la red en S3 -> settlement-batch -> Aurora y un resumen en S3.
5. **Historial de pagos**: payment-orders -> MSK -> payments-history -> Aurora (R2DBC); los consumidores de la API lo leen sin bloquear un hilo.

La saga de payment-process:

| Paso | Acción | Compensación |
|---|---|---|
| `processing` | orden -> PROCESSING (payment-orders) | orden -> REJECTED |
| `debit` | debitar la cuenta del cliente (banking-core) | revertir el débito |
| `transfer` | enviar la transferencia (pivote) | Ninguna |
| `complete` | orden -> COMPLETED (nunca hace fallar la saga; settlement la concilia) | Ninguna |

Cada llamada a un servicio posterior lleva una clave de idempotencia derivada de la orden y del paso,
de modo que los reintentos, los eventos reentregados y las sagas reanudadas tras una caída nunca
repiten un efecto.

## Ejecución local

Requisitos: JDK 25, Docker, `curl` y `jq`; Bruno para explorar las APIs. Los artefactos de axiz deben
estar disponibles: `scripts/build.sh --skip-tests` una vez en la raíz del repositorio, o el repositorio
de GitHub Packages en `~/.m2/settings.xml`. El [README raíz](../README.md) recorre el build, las dos
formas de ejecutar la demo y una llamada `curl` para cada endpoint con su respuesta real.

Hay dos formas de ejecutarla, con la misma infraestructura (`local/compose.yaml`):

- servicios en el host (`scripts/demo.sh`), depurables desde el IDE y necesarios para los escenarios
  funcionales, que leen los logs de los servicios;
- todo en Docker Compose (`scripts/compose.sh`): infraestructura, los ocho servicios como imágenes
  Jib (`local/compose.apps.yaml`, perfil `apps`), secretos de desarrollo y datos de arranque en un solo
  comando, que imprime todas las rutas al final.

```bash
scripts/demo.sh up       # comprueba Docker, JDK 25 y puertos libres, recompila jars desactualizados y levanta
                         # infraestructura (local/compose.yaml), secretos y servicios; espera 8/8 UP
scripts/demo.sh status   # estado de cada servicio: UP, STARTING, EXITED, BUSY (puerto ocupado), STOPPED
scripts/demo.sh smoke    # espera 8/8 UP, paga a un beneficiario por la API del canal y espera
                         # COMPLETED y el historial; un paso fallido imprime su respuesta HTTP
scripts/demo.sh scenarios   # todos los escenarios funcionales (unos 8 minutos, ver abajo)
scripts/demo.sh up --attach   # igual, y luego muestra los logs de cada servicio (Ctrl+C los detiene)
scripts/demo.sh logs          # sigue los logs de todos los servicios (scripts/demo.sh logs <servicio>: uno)
scripts/demo.sh restart payments-ux --debug   # reinicia un servicio con Spring web y axiz en DEBUG
scripts/demo.sh stop          # detiene los servicios y conserva la infraestructura
scripts/demo.sh down     # detiene servicios y elimina contenedores y volúmenes
```

```bash
scripts/compose.sh up --build      # jars e imágenes (sin pruebas), infraestructura, servicios y rutas
scripts/compose.sh up --no-build   # con las imágenes ya construidas
scripts/compose.sh status | routes | logs [service] | down
```

Ambas formas se comprueban con los mismos comandos:

```bash
scripts/curl-tests.sh    # cada endpoint en el orden del flujo de pago: estado HTTP y estado final
scripts/test-all.sh      # endpoints, prueba de humo y, con servicios en el host, escenarios funcionales
scripts/test-all.sh --dast   # además el escaneo OWASP ZAP autenticado de cada contrato
```

`up` ejecuta cada servicio desde su jar (`services/<service>/target`) y recompila los jars que faltan
o que son más antiguos que sus fuentes o que el framework axiz instalado en `~/.m2` (`--build`
recompila todos, `--no-wait` retorna de inmediato).
Se detiene antes de iniciar nada cuando un puerto está ocupado, e indica el contenedor o proceso que lo
ocupa (un clúster kind en el 8080 es el caso habitual).

| | |
|---|---|
| API de canal | <http://localhost:8080> (payments-ux) |
| Servicios | payment-orders 8081 / beneficiaries 8082 / banking-core 8083 / process 8084 / notifications 8085 / history 8086 / payment-network-gateway 8087 |
| Keycloak | <http://localhost:8180> (realm `axiz`, usuarios `ana` / `luis`, contraseñas `local-only-<user>`) |
| Grafana | <http://localhost:3000> (dashboards base *events*, *RED*, *messaging*, *dependencies*, más *axiz: services* y *axiz: payments*) |
| WireMock | <http://localhost:8089> (core bancario REST + SOAP, endpoint de tokens, red de pagos) |

Cada servicio se ejecuta con los perfiles `local,platform`, de modo que también puede arrancarse desde
el IDE y depurarse, o con Maven mediante `scripts/run.sh <service>` (primero se detiene la copia que
arrancó `demo.sh up`: comparten el puerto). El core simulado responde `CB-51` (fondos insuficientes)
para la cuenta `1910099999999` y `CB-62` (bloqueada) para `1910088888888`; la red rechaza el CCI
`00219100123456780052` (cuenta cerrada), para que las compensaciones se vean de extremo a extremo.

El job de liquidación carga el archivo de ejemplo que sube la inicialización de LocalStack:
`scripts/run.sh settlement-batch` (el perfil `platform` lo apunta a
`in/settlement/NET-SETTLE-SAMPLE.txt`). `demo.sh up` también compila su jar, y el grupo `settlement`
de los escenarios lo ejecuta.

### Escenarios funcionales

`scripts/demo.sh scenarios` (o `scripts/scenarios.sh`) comprueba lo que la demo promete, más allá del
pago único de la prueba de humo: 88 comprobaciones en 14 grupos, cada una imprime PASS o FAIL, con el
motivo y las últimas advertencias de los servicios involucrados cuando una comprobación falla.

| Grupo | Qué comprueba |
|---|---|
| `beneficiaries` | dígitos de control del CCI, un beneficiario activo por cuenta, renombrado (lecturas fuertemente consistentes), borrado lógico, idempotencia en DynamoDB, páginas con cursor, enmascaramiento en el canal |
| `payment` | un pago de extremo a extremo: canal, saga, vista del proceso, historial, orden |
| `idempotency` | repetición con la misma clave, misma clave con otro cuerpo, clave ausente |
| `rules` | pago duplicado, límite diario, feature flag de USD (mensajes en español e inglés), moneda que no coincide |
| `saga` | sin fondos, cuenta bloqueada, destino cerrado (el débito se revierte), cuenta de otro cliente; se libera el límite diario |
| `security` | 401, datos de otros clientes (404/403), cambios de estado y movimientos de dinero solo para payment-process, actuator protegido |
| `integration` | banking-core: verificación del titular, crédito SOAP, movimiento desconocido, rechazo de la red |
| `events` | outbox publicado, cuentas cifradas, estados de la saga, grupos de consumidores Kafka al día, DLQ vacía, cabeceras CloudEvents |
| `notifications` | solicitudes de notificación en SNS para pagos completados y rechazados |
| `audit` | auditoría por SQS archivada en S3 con su manifiesto, tópico de auditoría en Kafka, DLQ de auditoría vacía |
| `observability` | métricas de negocio y HTTP en Prometheus; trazas buscadas en Tempo por el trace ID de los logs: payment-process y banking-core en una sola traza, y una traza desde la petición del canal hasta la saga |
| `settlement` | el batch carga un archivo propio en cada ejecución, las reejecuciones no cargan nada, resumen en S3, un resumen perdido se vuelve a escribir al reejecutar |
| `dlq` | un mensaje mal formado termina en `payments.dlq` una vez por grupo de consumidores |
| `recovery` | un débito que agota el timeout deja la saga en VERIFYING; el job de recuperación la completa (unos 4 minutos) |

```bash
scripts/demo.sh scenarios saga security          # solo algunos grupos (crean los datos que necesitan)
SCENARIOS_SKIP="recovery" scripts/demo.sh scenarios
```

Los escenarios leen la infraestructura local (PostgreSQL, Kafka, LocalStack, WireMock, Grafana), por
lo que se ejecutan contra `demo.sh up`, no contra kind ni Compose. Necesitan el realm y la
inicialización de LocalStack de las fuentes vigentes: tras cambiarlos, se ejecuta una vez
`scripts/demo.sh down` y `scripts/demo.sh up`.

### Dentro de Kubernetes (kind)

```bash
kind/up.sh               # clúster kind, imágenes con Jib, dependencias simuladas (kind/deps) y cada chart de Helm
kind/up.sh down
```

El clúster kind publica la API de canal en el 9080, Keycloak en el 9180 y Grafana en el 9300, para
poder ejecutarse junto a la demo local. La prueba de humo contra él (lo que `kind/up.sh` imprime al final):

```bash
kubectl -n axiz-demo port-forward svc/beneficiaries 9082:80 &
kubectl -n axiz-demo port-forward svc/payments-history 9086:80 &
SMOKE_LOCAL=0 KC_URL=http://localhost:9180 CHANNEL_URL=http://localhost:9080 \
  CRUD_URL=http://localhost:9082 HISTORY_URL=http://localhost:9086 scripts/smoke.sh
```

Los servicios se despliegan con sus propios charts y `kind/values/<service>.yaml`; los secretos se
crean desde los archivos de secretos de desarrollo (los charts montan `<service>-secrets` tanto si lo
crea el External Secrets Operator como si no). El workflow nocturno `e2e` ejecuta exactamente esto más
la prueba de humo.

### Bruno

[`bruno/`](bruno) tiene una colección de peticiones con pruebas para cada servicio: autenticación, el
flujo del canal, llamadas M2M a business, process y banking-core, y casos de error
(su README explica las dos variables secretas).

## Ambientes

- `services/<service>/deploy/helm/<service>/values-<env>.yaml`: valores del ambiente (sin secretos).
- `deploy/platform/axiz-common.yaml`: configuración compartida por todos los servicios de un namespace
  (zona y calendario de negocio).
- Secretos: AWS Secrets Manager -> External Secrets Operator -> archivos en `/var/run/secrets/axiz`.

## Calidad

```bash
./mvnw verify            # todos los servicios (pruebas, linters, reglas de arquitectura) y la cobertura de capacidades
```

Las pruebas de integración de la aplicación son dueñas de su contexto Spring y de sus recursos de
Testcontainers. `@DirtiesContext(classMode = AFTER_CLASS)` los cierra después de la clase de prueba,
mientras JUnit sigue en ejecución, de modo que la limpieza de los contenedores termina antes de que
salga la JVM de pruebas. Las pruebas de aplicación generadas usan el mismo ciclo de vida.

- `coverage/`: falla cuando la demo no usa una capacidad del catálogo de axiz, cuando falta su resultado
  exitoso de JUnit, está omitido o desactualizado, o cuando falta una variante obligatoria de proveedor o
  de stack. Ver la [matriz ejecutable de capacidades](coverage/CAPABILITIES.md) y las [reglas del control](coverage/README.md).
- `.github/workflows/demo.yaml` (en la raíz del repositorio): cada servicio pasa por el workflow
  reutilizable `service-build` (calidad completa, pruebas, compatibilidad del contrato OpenAPI,
  validación del chart y escaneo de dependencias); en `main` publica las imágenes con sus
  atestaciones. `demo-nightly.yaml` despliega toda la demo en kind cada noche, con prueba de humo
  y DAST, y compila las imágenes nativas. `demo-deploy.yaml` promueve un digest atestiguado a dev,
  qa o prod.

Los [ejemplos ejecutables de datos protegidos](examples/protected-data/README.md) muestran la
tokenización de PAN y DNI y la búsqueda independiente de DNI cifrado con LocalStack y Valkey.
Se ejecutan como parte del reactor de la demo y no se despliegan con los servicios de pagos.

El ejemplo [`sqs-consumer`](examples/sqs-consumer/README.md) tiene un classpath mínimo y se apoya en el
autodescubrimiento de starters. Ejecuta consumo, reintentos, DLQ, redrive y FIFO en ambos stacks. La
[matriz ejecutable](coverage/CAPABILITIES.md) exige los stacks del catálogo y las variantes de
persistencia; la prueba HTTP de `payments-history` abre un servidor real en un puerto aleatorio y
comprueba el 401 de RFC 9457 con BlockHound activo.
