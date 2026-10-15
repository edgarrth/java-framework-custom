# Verificación de esta entrega

Sin acceso a Maven Central ni Docker en el entorno de construcción; `./mvnw verify`, las pruebas con
contenedores, el entorno local y el e2e en kind corren en CI (`ci.yaml`, `e2e.yaml`).

| Qué | Resultado local |
|---|---|
| Compilación (main y pruebas) de los 8 servicios | OK |
| Pruebas unitarias + `ArchitectureTest` | 94 OK: payments-business 15, beneficiaries-crud 15, payments-experience 10, core-integration 13, payment-process 12, notifications-processor 6, settlement-batch 11, payments-history 12 |
| Cobertura de capacidades (`coverage/`) | 2 OK: todas las capacidades del catálogo están usadas y su evidencia existe |
| Charts (8 servicios × dev/qa/prod) | kubeconform estricto y kube-linter sin hallazgos |
| Workflows y scripts | actionlint + shellcheck sin hallazgos |
| `kind/deps` | manifiestos válidos (kubeconform) |
| Migraciones y SQL sobre PostgreSQL 16 real | las migraciones de los 4 servicios con base de datos se aplican en orden sin errores; las columnas de `PaymentOrderEntity` coinciden con el tipo que Hibernate valida (encontró `currency CHAR(3)`, corregido con `V4__currency_varchar.sql`); las sentencias SQL de outbox, secuencias, sagas e inbox R2DBC del framework y las consultas nativas del demo se preparan (`PREPARE`) contra esos esquemas sin errores. Las migraciones de los 10 proyectos generados con Aurora también se aplican sin errores |
| `scripts/demo.sh` y `scripts/smoke.sh` | shellcheck sin hallazgos; ejecutados contra servicios simulados: flujo completo OK, fallos de token, de pago y de servicios caídos con mensaje y código de salida 1, puerto ocupado por un nodo de kind detectado, servicio que se cae al arrancar detectado con el final de su log |

Omitido localmente: `DailyLimitConcurrencyIT` (concurrencia real sobre PostgreSQL), pruebas de arranque (`*ApplicationTest`, Testcontainers; la de payments-history
recorre Kafka → inbox R2DBC → bus reactivo → PostgreSQL), `ContractExamplesTest`,
la colección de Bruno y el smoke test, que necesitan los servicios corriendo.
