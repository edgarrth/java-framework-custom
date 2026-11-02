# Capas del framework

Axiz 2.0 separa contratos, tecnología, integración con Spring y organización del servicio.
Los arquetipos describen el rol del servicio y no son capas de ejecución.

| Capa | Carpeta | Artefactos | Parent y responsabilidad |
|---|---|---|---|
| Plataforma | `platform/` | `axiz-parent-*`, `axiz-dependencies`, `axiz-bom` | Versiones, dependencias y controles de build |
| 1. Core | `core/` | `axiz-core-<capacidad>` | `axiz-parent-core`: puertos, tipos y lógica sin tecnología ni runtime |
| 2. Tecnología | `adapters/` | `axiz-adapter-<capacidad>-<tecnología>` | `axiz-parent-technology`: implementa los puertos con un SDK o driver |
| 2. Persistencia | `kits/` | `axiz-kit-jpa`, `axiz-kit-r2dbc`, `axiz-kit-dynamodb` | `axiz-parent-technology`: tipos y herramientas de persistencia |
| 3. Spring | `spring/` | `axiz-<capacidad>-spring-boot-autoconfigure` y `-starter` | `axiz-parent-spring`: lifecycle, beans, transacciones y fachadas |
| 4. Estructuras | `structures/` | `axiz-structure-<estructura>-api` y `-spring-boot-starter` | Una estructura por servicio |
| 4. Extensiones | `extensions/` | `axiz-extension-cqrs-api` y `-spring-boot-starter` | Capacidades estructurales opcionales; CQRS se monta sobre hexagonal |
| Generación | `codegen/` | Catálogo, plantillas y `axiz-codegen` | Modelo compartido por CLI e Initializr |
| Calidad | `quality/` | Reglas, contratos y soporte de pruebas | `axiz-parent-test` para soporte de pruebas; sin dependencia productiva desde core |

## Contratos y tecnología

Core admite JDK, JSpecify, SLF4J, Micrometer y otros módulos core. Prohíbe Spring, Quarkus,
Reactor, Reactive Streams y SDKs de tecnología. Publica puertos síncronos y puertos `Async*`
con `CompletionStage` o `Flow.Publisher`; los adaptadores tecnológicos implementan esos puertos.

Los kits son herramientas de persistencia. Una entidad JPA puede extender `AuditableEntity`;
una fila R2DBC puede usar `EncryptedText`; un repositorio DynamoDB puede usar `DynamoRepository`.
Estos tipos se importan en `repository` o `adapter.out.persistence`, nunca en el dominio.
El dominio puede usar contratos de Axiz sin adquirir una dependencia de un framework tercero.

La excepción de Reactor en tecnología es cerrada: `axiz-kit-r2dbc`,
`axiz-adapter-inbox-r2dbc` y `axiz-adapter-outbox-r2dbc`. Conservan Reactor para transportar
la transacción en el contexto. Los adaptadores AWS pueden interoperar internamente con Reactive
Streams, pero sus APIs públicas exponen los tipos neutrales del puerto.

## Integración y estructura

Spring registra autoconfiguraciones en `AutoConfiguration.imports`, usa propiedades tipadas,
permite reemplazar beans y selecciona la variante de ejecución. Una capacidad Spring depende de
su propio paquete y de `foundation`; conversa con otras capacidades mediante puertos core.
Los starters agrupan dependencias y no contienen otra implementación del algoritmo core.

Las estructuras y extensiones componen capacidades y entregan contratos, integración, plantillas
y reglas. Sus módulos `-api` usan `axiz-parent-api`; los starters usan `axiz-parent-spring`.
Los contratos reactivos de estructura pueden usar `Publisher`, sin importar Spring ni Reactor.

El servicio declara su estructura, sus extensiones y los starters de capacidades elegidos.
Los kits se declaran en compile cuando son necesarios; los adaptadores tecnológicos, en runtime.
La aplicación no importa autoconfiguraciones ni implementaciones `pe.axiz.adapter.*`.

## Verificación

Enforcer controla dependencias y scopes; `FrameworkArchitectureTest` controla el uso de clases,
firmas públicas, aislamiento y ciclos. `check-layer-structure.py` comprueba nombres, parents,
pertenencia al reactor y la lista cerrada de módulos R2DBC.
Cada servicio ejecuta [sus reglas de arquitectura](../calidad/arquitectura.md).
