# Reglas de arquitectura

Cada servicio corre sus reglas en `ArchitectureTest`:

```java
class ArchitectureTest {
  @Test
  void followsTheArchitecture() {
    AxizArchitecture.of("pe.axiz.payments.orders")
        .archetype(Archetype.BUSINESS)
        .stack(Stack.SERVLET)
        .structure(Structure.HEXAGONAL)
        .extensions(Extension.CQRS)
        .verify();
  }
}
```

`verify` importa las clases de producción del paquete y revisa las reglas del arquetipo, stack, estructura y extensiones más las
generales, fallando con **todas** las violaciones a la vez.

## Reglas generales

| Regla | Por qué |
|---|---|
| Inyección solo por constructor (sin `@Autowired` en campos) | dependencias explícitas y beans inmutables |
| Sin estado mutable en singletons (campos no `final` en beans) | los datos de una request nunca se guardan en un campo compartido |
| Sin `System.out` / `System.err` | logs estructurados |
| Sin excepciones genéricas (`Exception`, `RuntimeException`) lanzadas directamente | errores con categoría y código |
| Sin `java.util.logging` ni Joda-Time | SLF4J y `java.time` |
| Sin hooks globales de Reactor | la propagación de contexto la hace Micrometer |
| Sin `block()` en código reactivo | bloquear agota el event loop |
| Sin `TestingAuthenticationToken` en código productivo | autenticación real siempre |
| Sin Retrofit, Feign ni OkHttp | un solo tipo de cliente: `@HttpExchange` |
| Sin AWS SDK v1 | un solo SDK: v2 |
| Sin APIs `@Deprecated` (Spring, AWS SDK, Jackson, axiz) | un framework nuevo no arranca con deuda: lo deprecado se reemplaza, no se suprime |

## Reglas por estructura y extensión

| Selector | Reglas |
|---|---|
| Layered | `controller → service → repository`; controladores y repositorios en su paquete |
| Hexagonal | dependencias hacia adentro; dominio sin Spring, JPA, AWS SDK ni Jackson; aplicación sin Spring Web, JPA ni AWS SDK; nombres en su paquete |
| Extensión CQRS | las de hexagonal; commands y queries como records; queries (bloqueantes o reactivas) sin depender de commands ni del `ReactiveCommandBus` |
| EDA | handlers en `handler`; sin controladores REST |
| BFF | sin JPA, Spring Data JPA ni DynamoDB Enhanced |
| Batch | `*Processor` en `job`; sin controladores REST |

## Reglas del propio framework

`axiz-architecture-tests` verifica la independencia de core, el aislamiento de tecnología,
las firmas públicas neutrales de adaptadores, la excepción cerrada de Reactor en R2DBC y
la comunicación entre capacidades Spring por puertos. Los `-api` estructurales no importan
Spring ni Reactor; los contratos reactivos admiten Reactive Streams.

Un paquete base sin clases de producción hace fallar `verify`: una errata en el paquete no
puede convertir las reglas en una prueba vacía. El rol restringe las llamadas y la persistencia;
`ReactiveRules` prohíbe puertos síncronos, SDKs bloqueantes, esperas y offload en el flujo reactive.
