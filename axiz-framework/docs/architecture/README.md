# Arquitectura del framework

Axiz organiza el SDK en capas con dependencias verificadas durante el build. Los arquetipos
seleccionan el rol de un servicio; las estructuras organizan su código; las capacidades
incorporan comportamiento e integración.

## Capas y dependencias

| Carpeta | Contrato |
|---|---|
| `platform` | Parents, BOM y recursos SQL; configura builds y versiones |
| `core` | Puertos y algoritmos con JDK, JSpecify, SLF4J y Micrometer; sin Spring, Reactor ni SDKs |
| `adapters` | Implementaciones con clientes nativos; depende de core y de los contratos tecnológicos necesarios |
| `kits` | APIs de persistencia; no depende de Spring ni de capas superiores |
| `spring` | Composición de beans, transacciones, propiedades y fachadas servlet/reactivas |
| `structures` | Una estructura por servicio: layered, hexagonal, EDA, BFF o batch |
| `extensions` | Extensiones opcionales; CQRS utiliza contratos y buses sobre hexagonal |
| `codegen` | Motor, catálogos y plantillas compartidos por CLI e Initializr |
| `quality` | Estándares, pruebas y reglas ArchUnit |

Los módulos `-api` de estructuras y extensiones son independientes entre sí. Sus starters
componen las capacidades Spring. Se rechazan los ciclos y las dependencias de una capa baja
hacia una capa de composición.

## Dominio y adaptadores de un servicio

En hexagonal, `domain` contiene tipos y reglas de negocio. Puede usar contratos de Axiz y el
JDK, sin anotaciones Spring, entidades JPA, SDKs o clientes HTTP. `application` orquesta casos
de uso y puertos; `adapter.in` recibe peticiones o eventos; `adapter.out` integra persistencia
y sistemas externos. La configuración une los puertos con sus implementaciones.

Los kits tienen scope compile cuando un repositorio necesita sus tipos. Los adaptadores
tecnológicos se declaran en runtime; el servicio no importa `pe.axiz.adapter.*` ni clases
`*AutoConfiguration`. Las entidades JPA y las filas R2DBC permanecen en persistencia.

Los paquetes de infraestructura dependen del dominio; las reglas de negocio no dependen de
la implementación que guarda o transmite sus datos. Los adaptadores de AWS comparten la
resolución de identidad y los transportes, sin introducir esas dependencias en core.

## Modelos de ejecución

Servlet utiliza Spring MVC, virtual threads y puertos síncronos. Reactive utiliza WebFlux,
puertos asíncronos nativos y R2DBC. Core expone `CompletionStage` o `Flow.Publisher`; Spring
los adapta a Reactor conservando errores, demanda y cancelación.

`axiz-kit-r2dbc` y los adaptadores inbox/outbox R2DBC conservan Reactor para propagar la
transacción. Los hilos de poll pueden esperar con plazo al handler para resolver acknowledgements;
los handlers y las peticiones reactivas mantienen su modelo de I/O asíncrono. Kafka delega la
entrada a `send()` en un ejecutor acotado cuando el cliente puede esperar por metadatos.

Ver [servlet y reactive](../../../axiz-docs/docs/arquitectura/stack.md) y
[límites JDBC](JDBC.md).

## Generación y configuración

El catálogo incluye roles business, core, gateway, ux, support, event-processor y batch, además
de library y starter. Cada rol admite las estructuras, stacks y persistencias declaradas en
el [catálogo](../../../axiz-docs/docs/arquetipos/catalogo.md). La selección se registra en
`axiz.yaml`; CLI e Initializr rechazan combinaciones no admitidas.

Las capacidades se activan mediante starters y propiedades tipadas. Los secretos pertenecen
al proveedor configurado; los archivos de configuración describen referencias y opciones de
cada entorno. [AWS](../../../axiz-docs/docs/arquitectura/aws.md) documenta roles IAM,
credenciales temporales, selección de identidad y ciclo de vida.

## Validación

Maven Enforcer comprueba dependencias y scopes. `FrameworkArchitectureTest` comprueba imports,
firmas públicas, aislamiento y ciclos. Los servicios generados incluyen `ArchitectureTest`;
las integraciones reactivas usan BlockHound. El perfil `quality` ejecuta formato, análisis
estático, cobertura y pruebas de mutación según el módulo.

La [política de API pública](../api/README.md) describe las verificaciones de firmas y
compatibilidad de los artefactos publicados. Los controles de build y deploy están en
[pipelines](../../../.github/docs/pipelines-v2.md).
