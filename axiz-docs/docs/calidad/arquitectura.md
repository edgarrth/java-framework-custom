# Reglas de arquitectura

Cada servicio corre sus reglas en `ArchitectureTest`:

```java
class ArchitectureTest {
  @Test
  void followsTheArchitecture() {
    AxizArchitecture.verify("pe.axiz.payments.business", AxizArchitecture.Style.CQRS);
  }
}
```

`verify` importa las clases de producción del paquete y revisa las reglas del estilo más las
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

## Reglas por estilo

| Estilo | Reglas |
|---|---|
| Layered | `controller → service → repository`; controladores y repositorios en su paquete |
| Hexagonal | dependencias hacia adentro; dominio sin Spring, JPA, AWS SDK ni Jackson; aplicación sin Spring Web, JPA ni AWS SDK; nombres en su paquete |
| CQRS | las de hexagonal; commands y queries como records; queries (bloqueantes o reactivas) sin depender de commands ni del `ReactiveCommandBus` |
| EDA | handlers en `handler`; sin controladores REST |
| BFF | sin JPA, Spring Data JPA ni DynamoDB Enhanced |
| Batch | `*Processor` en `job`; sin controladores REST |

## Reglas del propio framework

`axiz-architecture-tests` verifica que la capa 1 sea Java puro, que la capa 2 no conozca la 3, que
los `-api` de los estilos dependan solo de la capa 1, que cada starter use solo su capacidad o la
fundación y que no haya ciclos entre capacidades ni entre estilos.
