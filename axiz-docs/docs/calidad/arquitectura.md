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
| Tipos mutables en singletons requieren `@ReviewedState` | `final` no hace inmutable una colección; incluye instancias creadas mediante `@Bean` |
| Sin `float`/`double` en campos, parámetros ni resultados de dominio, aplicación, puertos y DTO de adaptadores | preservar cantidades decimales exactas también en las fronteras |
| Sin construir `BigDecimal` desde `double` | evitar introducir aproximaciones binarias |
| Reloj inyectado en dominio y negocio: prohíbe `now()`, `now(ZoneId)` y `Clock.systemUTC()`/`systemDefaultZone()`/`system()`; se usa `now(clock)` | pruebas reproducibles y una fuente de tiempo explícita |
| Sin motores JCA directos (`MessageDigest`, `Cipher`, `Mac`, `Signature`, `KeyGenerator`, `SecretKeyFactory`, `KeyPairGenerator`) ni `java.util.Random`/`Math.random` | algoritmo, modo y aleatoriedad se revisan una vez en `axiz-core-crypto` (`Hashing`, `AesGcm`, `FieldEncryptor`, `SecureRandoms`): nada de MD5, ECB o semillas predecibles |
| Sin `@Transactional` en clases de dominio o transporte | la transacción pertenece al servicio de aplicación |
| Sin `@Transactional` en métodos de dominio o transporte | el control también cubre anotaciones en métodos |
| Sin `System.out` / `System.err` | logs estructurados |
| Sin excepciones genéricas (`Exception`, `RuntimeException`) lanzadas directamente | errores con categoría y código |
| Sin `java.util.logging` ni Joda-Time | SLF4J y `java.time` |
| Sin hooks globales de Reactor | la propagación de contexto la hace Micrometer |
| Sin `block()` en código reactivo | bloquear agota el event loop |
| Sin `TestingAuthenticationToken` en código productivo | autenticación real siempre |
| Sin Retrofit, Feign ni OkHttp | un solo tipo de cliente: `@HttpExchange` |
| Sin AWS SDK v1 | un solo SDK: v2 |
| Sin dependencias directas en `pe.axiz.adapter` | los servicios consumen puertos y fachadas; Spring compone proveedores |
| Sin depender de clases `*AutoConfiguration` | la configuración interna no es una API de negocio |
| Kits solo en repositorios y `adapter.out.persistence` | los detalles tecnológicos permanecen fuera de la aplicación |
| Sin APIs `@Deprecated` (Spring, AWS SDK, Jackson, axiz) | un framework nuevo no arranca con deuda: lo deprecado se reemplaza, no se suprime |
| Nombres de beans únicos | evitar colisiones entre componentes y métodos `@Bean` |
| Sin locks JVM en paquetes de negocio | una exclusión en un proceso no coordina réplicas |
| Sin métodos de negocio `synchronized` | usar invariantes transaccionales o coordinación distribuida |

La tabla cubre las 24 reglas de `GeneralRules.all()`; `System.out` y `System.err` forman una regla.

## Reglas por estructura y extensión

| Selector | Reglas |
|---|---|
| Layered | `controller -> service -> repository`; controladores y repositorios en su paquete |
| Hexagonal | dependencias hacia adentro; dominio sin Spring, JPA, AWS SDK ni Jackson; aplicación sin Spring Web, JPA ni AWS SDK; nombres en su paquete |
| Extensión CQRS | las de hexagonal; commands y queries como records; queries (bloqueantes o reactivas) sin depender de commands ni del `ReactiveCommandBus` |
| EDA | handlers en `handler`; sin controladores REST |
| BFF | sin JPA, Spring Data JPA ni DynamoDB Enhanced |
| Batch | `*Processor` en `job`; sin controladores REST |

## Reglas del propio framework

`axiz-architecture-tests` verifica la independencia de core, el aislamiento de tecnología,
las firmas públicas neutrales de adaptadores, las excepciones cerradas de Reactor (cuatro
módulos R2DBC y dos contratos neutrales de auditoría/outbox) y
la comunicación entre capacidades Spring por puertos. Los `-api` estructurales no importan
Spring ni Reactor; los contratos reactivos admiten Reactive Streams.

Un paquete base sin clases de producción hace fallar `verify`: una errata en el paquete no
puede convertir las reglas en una prueba vacía. El rol restringe las llamadas y la persistencia;
`ReactiveRules` prohíbe puertos síncronos, SDKs bloqueantes, esperas y offload en el flujo reactive.

## Ejecución obligatoria y datos sensibles

Los proyectos nuevos generan `ArchitectureTest.architecture()` y ambos tests usan esa misma
configuración. `ArchitectureSelfCheckTest` ejecuta un fixture prohibido incluido en el artefacto
de pruebas: detecta reglas vacías o desconectadas. No sustituye las pruebas positivas/negativas
de cada regla. El pipeline exige informes Surefire de ambas clases, con tests realmente
ejecutados, sin omisiones, fallos ni errores. Limpia previamente los XML de ejecuciones anteriores.
`axiz doctor` exige ambos archivos; la mera presencia del código no demuestra ejecución.

Los scopes explícitos son `CardDataScope.NONE|EDGE|VAULT` y
`TokenizationScope.NONE|CLIENT|VAULT`. El generador los obtiene de las capacidades resueltas:
cliente remoto no puede importar el motor ni proveedores de bóveda; DNI sin PAN no necesita
`carddata`. `axiz add` actualiza los scopes de la configuración generada al agregar esas capacidades.
Un proyecto anterior debe migrar primero a declaraciones explícitas; no se sustituye una
configuración personalizada sin poder identificar sus selectores.

Los paquetes admitidos están cerrados por estructura y el bootstrap real contiene solo `main`.
Se revisa el dominio contra una lista de paquetes JDK y APIs permitidos, I/O, reloj ambiental,
flotantes para negocio y transacciones en lugares indebidos. Un campo `final` que apunta a una
colección sigue siendo mutable: `@ReviewedState` exige una razón explícita para el estado
compartido revisado, incluidos tipos concretos creados por `@Bean`.

El análisis de datos persistidos considera entidades JPA/DynamoDB, eventos y contextos de saga,
colecciones, genéricos y getters persistentes. Una anotación de cifrado en el lado que JPA no
usa no cuenta como protección. CVV/PIN/tracks no pueden almacenarse. Fuera de VAULT, PAN tampoco.
La exploración es finita: rechaza grafos locales incompletos o que excedan cuatro niveles para
que la profundidad no oculte un campo. El bytecode no prueba una configuración programática de
mapeo ni el significado de todos los nombres del negocio; esas variantes necesitan revisión y
pruebas de persistencia. ArchUnit no acredita cumplimiento PCI DSS por sí solo.
