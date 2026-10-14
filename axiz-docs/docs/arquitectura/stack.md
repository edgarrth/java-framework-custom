# Servlet y reactivo

El stack es un eje que se combina con el estilo, no un framework aparte.

**Servlet (por defecto).** Spring MVC con `spring.threads.virtual.enabled=true`: cada request corre
en un virtual thread, así el servicio escala bien mientras espera I/O con código secuencial simple,
compatible con JPA y JDBC.

**Reactivo (opcional).** WebFlux y Reactor, para un BFF con mucho fan-out, streaming (SSE,
WebSocket), consumo de eventos con backpressure o servicios cuyas dependencias son todas no
bloqueantes. No se recomienda frente a sistemas legacy con drivers bloqueantes.

Cada starter con comportamiento web trae ambas variantes en el mismo artefacto; la
auto-configuración activa la correcta y el servicio declara un solo starter.

| Capacidad | Servlet | Reactivo |
|---|---|---|
| Errores | `@RestControllerAdvice` | `ErrorWebExceptionHandler` |
| Trazas y contexto | filtro servlet + MDC | `WebFilter` + Reactor Context con context-propagation |
| Seguridad | `SecurityFilterChain` | `SecurityWebFilterChain` |
| Log HTTP y auditoría | filtro servlet | `WebFilter` |
| Cliente HTTP | `RestClient` | `WebClient` |
| Idempotencia y rate limit | filtro servlet | `WebFilter` |
| Caché | `@AxizCacheable` sobre valores | `@AxizCacheable` sobre `Mono` y `Flux` |
| Composición BFF | `Composer` en virtual threads | `ReactiveComposer` |
| Aurora PostgreSQL | JPA + JDBC (capacidad `jpa`) | R2DBC (capacidad `r2dbc`) |
| Transacciones | `PlatformTransactionManager` | `R2dbcTransactionManager` / `TransactionalOperator` |
| Outbox, inbox, ShedLock | JDBC | R2DBC (`ReactiveTransactionalEvents`, `axiz.inbox.store: r2dbc`, `axiz.scheduling.store: r2dbc`) |
| CQRS | `CommandBus`, `QueryBus` | `ReactiveCommandBus`, `ReactiveQueryBus` |
| EDA | `TypedEventHandler` | `ReactiveTypedEventHandler` |
| Pruebas | — | `BlockHoundExtension` obligatoria |

Estilos con variante reactiva: `cqrs`, `bff` y `eda`. `layered` y `hexagonal` son solo servlet.

!!! note "Persistencia relacional"
    Aurora PostgreSQL (aprovisionado) sirve a los dos stacks con el mismo esquema: en servlet por
    JPA/JDBC y en reactivo por **R2DBC**, que no bloquea el event loop. `--persistence aurora` con
    `--stack reactive` agrega la capacidad `r2dbc` en lugar de `jpa`, y las capacidades con almacén
    (outbox, inbox, scheduling) toman su variante R2DBC. Flyway sigue migrando por JDBC al
    arrancar. JPA, sagas y secuencias, que son JDBC, no están disponibles en reactivo: el
    generador lo explica al validar. DynamoDB sigue disponible en ambos stacks (en reactivo los
    handlers CQRS son bloqueantes y el controlador los ejecuta en `boundedElastic`).

## Contexto sin hooks globales

El contexto de la request (correlación, tenant, headers de negocio, principal) viaja en el MDC en
servlet y en el Reactor Context en reactivo, conectados con Micrometer context-propagation. Está
prohibido usar `Hooks.onEachOperator` u otros hooks globales de Reactor (lo revisan ArchUnit y
Semgrep), igual que bloquear dentro de un operador.
