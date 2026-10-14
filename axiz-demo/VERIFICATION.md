# Verificación de esta entrega

Sin acceso a Maven Central ni Docker en el entorno de construcción; `./mvnw verify`, las pruebas con
contenedores, el entorno local y el e2e en kind corren en CI (`ci.yaml`, `e2e.yaml`).

| Qué | Resultado local |
|---|---|
| Compilación (main y pruebas) de los 8 servicios | OK |
| Pruebas unitarias + `ArchitectureTest` | 92 OK: payments-business 15, beneficiaries-crud 13, payments-experience 10, core-integration 13, payment-process 12, notifications-processor 6, settlement-batch 11, payments-history 12 |
| Cobertura de capacidades (`coverage/`) | 2 OK: todas las capacidades del catálogo están usadas y su evidencia existe |
| Charts (8 servicios × dev/qa/prod) | kubeconform estricto y kube-linter sin hallazgos |
| Workflows y scripts | actionlint + shellcheck sin hallazgos |
| `kind/deps` | manifiestos válidos (kubeconform) |

Omitido localmente: `DailyLimitConcurrencyIT` (concurrencia real sobre PostgreSQL), pruebas de arranque (`*ApplicationTest`, Testcontainers; la de payments-history
recorre Kafka → inbox R2DBC → bus reactivo → PostgreSQL), `ContractExamplesTest`,
la colección de Bruno y el smoke test, que necesitan los servicios corriendo.
