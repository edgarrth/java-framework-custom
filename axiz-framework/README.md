# axiz-framework

Framework corporativo de backend para microservicios Java 25 sobre Spring Boot 4.1, Kubernetes (EKS) y AWS.
Paquete raíz `pe.axiz`, groupId `pe.axiz` y una única versión `${revision}` para los artefactos del framework. Las herramientas, la documentación y los workflows tienen versiones independientes dentro del monorepo.

## Capas

| Capa | Carpeta | Artefactos | Regla |
|---|---|---|---|
| Plataforma | `platform/` | `axiz-parent-*`, `axiz-dependencies`, `axiz-bom` | Versiones y controles del build |
| 1. Core | `core/` | `axiz-core-<capability>` | JDK, SLF4J, JSpecify y contratos del núcleo; Micrometer solo en metrics/observability; sin proveedores ni runtime de aplicación |
| 2. Adaptadores | `adapters/` | `axiz-adapter-<capability>-<technology>` | Implementan los puertos del núcleo con clientes nativos de cada tecnología |
| 2. Kits de persistencia | `kits/` | `axiz-kit-jdbc`, `axiz-kit-jpa`, `axiz-kit-r2dbc`, `axiz-kit-dynamodb` | Tipos de persistencia; los usan solo los repositorios y adaptadores de persistencia de los servicios |
| 3. Spring | `spring/` | `axiz-<capability>-spring-boot-autoconfigure` y `-starter` | Ciclo de vida, beans, transacciones y fachadas; las capacidades se comunican mediante los puertos del núcleo |
| 4. Estructuras | `structures/` | `axiz-structure-<structure>-api` y `-spring-boot-starter` | Exactamente una estructura por servicio |
| 4. Extensiones | `extensions/` | `axiz-extension-cqrs-api` y `-spring-boot-starter` | CQRS opcional en servicios hexagonales de negocio o procesadores de eventos |
| Generación | `codegen/` | `axiz-codegen`, plantillas, arquetipos | Compartida por la CLI y el Initializr |
| Calidad | `quality/` | Estándares, ArchUnit, soporte de pruebas y contratos de almacenes | Pruebas y cumplimiento de la arquitectura |

`axiz-adapter-aws` es la base compartida de identidad y transporte de AWS; no depende de Spring ni de
Micrometer. Spring es dueño de la composición del contexto y el adaptador opcional de observabilidad
añade la telemetría.

La [guía de arquitectura](../axiz-docs/docs/arquitectura/capas.md) describe los límites de los servicios, los modelos de ejecución y las reglas de dependencias. El [catálogo de capacidades](../axiz-docs/docs/capacidades/index.md) lista los starters, la configuración y los ejemplos.

## Compilación

```bash
./mvnw --bootstrap install         # copia nueva: BOM independientes, luego parents y módulos en ~/.m2
./mvnw verify                      # compilación, pruebas unitarias y reglas base de enforcer
./mvnw -Pquality verify            # control de CI: además Spotless, Checkstyle, Error Prone con NullAway, convergencia de
                                   # dependencias, SpotBugs/FindSecBugs, umbrales de JaCoCo y PIT en core
./mvnw -Pquality,release deploy -DaltDeploymentRepository=staging::file:///tmp/axiz-staging  # staging local del candidato
./mvnw -Pquality -pl '!:axiz-dependencies,!:axiz-bom' spotless:apply  # formato
```

Requisitos: JDK 25 y Maven 3.9.x (wrapper incluido). La configuración de Maven con el repositorio y
las credenciales de GitHub Packages proviene de los secretos de CI o de `~/.m2/settings.xml`, nunca de
este repositorio.

## Capacidades del núcleo (capa 1)

`commons`, `errors`, `i18n`, `domain`, `money`, `time`, `identifiers`, `id`, `sensitive`,
`logging`, `observability`, `metrics`, `audit`, `security`, `crypto`, `secrets`, `tenancy`,
`resilience`, `http`, `soap`, `messaging`, `outbox`, `inbox`, `idempotency`, `cache`,
`sequence`, `lock`, `storage`, `featureflags`, `fileformats`, `notification` y `saga`.

Las implementaciones de Redis, AWS, Kafka y persistencia están en la capa 2. El soporte de pruebas y
los contratos compartidos de almacenes están en `quality`; no son capacidades del núcleo de producción.

Cada módulo tiene su propio README con sus tipos públicos.

## Convenciones

- Propiedades: `axiz.<capability>.*` (kebab-case). Códigos de error: `AXZ-<CAPABILITY>-<NNNN>`.
- Todos los paquetes son `@NullMarked` (JSpecify); NullAway lo comprueba en compilación.
- El formato lo gestiona Spotless (google-java-format); Checkstyle solo comprueba nombres, imports,
  el Javadoc de la API pública y las APIs prohibidas.
- El README del módulo documenta la configuración, los contratos y los ejemplos de uso.

## Límites de responsabilidad

Los contratos y algoritmos del núcleo son independientes de la tecnología y de los frameworks de
runtime. Los puertos asíncronos nativos usan `CompletionStage` o `Flow.Publisher`. Los adaptadores de
tecnología implementan estos puertos; los kits de persistencia exponen sus tipos de tecnología de forma
deliberada.

La lista cerrada de Reactor contiene cuatro integraciones de persistencia: `axiz-kit-r2dbc`,
`axiz-adapter-inbox-r2dbc`, `axiz-adapter-outbox-r2dbc` y `axiz-adapter-sequence-r2dbc`.
Dos kits neutrales, `axiz-kit-audit-reactor` y `axiz-kit-outbox-reactor`, exponen los contratos
transaccionales sin incorporar drivers R2DBC ni una implementación de almacenamiento. Los adaptadores
de AWS pueden usar Reactive Streams internamente para interoperar con el SDK sin exponerlo en su API
pública.

Spring añade el ciclo de vida, las transacciones, la seguridad y las fachadas servlet y reactivas. Los
filtros e interceptores integran los algoritmos del núcleo con el runtime. Los starters ensamblan las
dependencias; los servicios consumen el starter de pruebas con scope test.

Un servicio elige un arquetipo, un stack de ejecución, una estructura y extensiones opcionales. CQRS es
una extensión de hexagonal; los handlers de eventos, el inbox y el outbox son capacidades
independientes de EDA. Los adaptadores del servicio son dependencias de runtime; los kits pertenecen a
los paquetes de persistencia. El dominio puede depender de los contratos de dominio de Axiz y seguir
siendo independiente de frameworks de terceros.

| Capacidades relacionadas | Responsabilidades distintas |
|---|---|
| logging / observability / metrics / audit | Diagnóstico; observaciones y trazas; instrumentos acotados de eventos de negocio; eventos de auditoría de negocio |
| idempotency / inbox / outbox / lock | Repetir la respuesta de una operación; deduplicar mensajes recibidos; publicar cambios confirmados; coordinar propietarios concurrentes |
| security / crypto / sensitive / secrets | Autenticación y autorización; criptografía; enmascaramiento; obtención de material de claves y credenciales |
| messaging / notification | Transportar mensajes; solicitar la entrega a un destinatario humano |
| id / identifiers / sequence | Generar IDs técnicos; validar identificadores de dominio; asignar números de negocio secuenciales |
| persistence / storage | Guardar registros de aplicación; guardar archivos y objetos |
| platform / codegen / quality | Gobernar dependencias y builds; generar el código de los servicios; hacer cumplir la arquitectura y la calidad |

La CLI y el Initializr comparten `axiz-codegen`. Las plantillas y los metadatos de arquetipos son
recursos de generación, no implementaciones alternativas de runtime. La demo, la documentación y los
workflows son partes mantenidas de la distribución, aunque no sean capas de runtime.
