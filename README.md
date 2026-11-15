# Axiz

Framework para desarrollar servicios backend en Java 25 y Spring Boot 4.1. Proporciona
contratos de dominio, integración con tecnologías, configuración de infraestructura,
generación de proyectos y controles de calidad para aplicaciones financieras.

## Proyectos del repositorio

| Proyecto | Contenido | Documentación |
|---|---|---|
| [axiz-framework](axiz-framework/README.md) | SDK Java, puertos, adaptadores, persistencia, starters, estructuras, generador y reglas de arquitectura | [Capas y dependencias](axiz-docs/docs/arquitectura/capas.md), [referencia de capacidades](axiz-docs/docs/capacidades/index.md) |
| [axiz-tools](axiz-tools/README.md) | CLI `axiz`, Initializr y recetas OpenRewrite | [CLI](axiz-tools/axiz-cli/README.md), [Initializr](axiz-tools/axiz-initializr/README.md), [recetas](axiz-tools/axiz-rewrite-recipes/README.md) |
| [axiz-demo](axiz-demo/README.md) | Nueve servicios que muestran un flujo de pagos con las capacidades del framework | [Servicios y ejecución local](axiz-demo/README.md), [contratos](axiz-demo/contracts) |
| [axiz-docs](axiz-docs/README.md) | Guías de uso, referencia de módulos y sitio MkDocs para GitHub Pages | [Inicio](axiz-docs/docs/index.md), [primer servicio](axiz-docs/docs/empezar/index.md) |
| [.github](.github/WORKFLOWS.md) | Workflows de build, deploy y publicación, acciones reutilizables y configuración GitOps | [Pipelines](.github/docs/pipelines.md), [destinos GitOps](.github/gitops/README.md) |

Los componentes comparten repositorio. Framework, herramientas, documentación y workflows
se versionan de forma independiente. El procedimiento de publicación está en
[RELEASING.md](RELEASING.md).

## Organización del framework

| Capa | Responsabilidad |
|---|---|
| [Platform](axiz-framework/platform/README.md) | Parents Maven, BOM, versiones, esquema SQL y configuración del build |
| [Core](axiz-framework/core/README.md) | Tipos, puertos y algoritmos independientes de Spring y clientes tecnológicos |
| [Adapters](axiz-framework/adapters/README.md) | Implementaciones de puertos con Redis, Kafka, AWS y bases de datos |
| [Kits](axiz-framework/kits/README.md) | APIs de persistencia JDBC, JPA, R2DBC y DynamoDB |
| [Spring](axiz-framework/spring/README.md) | Autoconfiguración, propiedades tipadas, ciclo de vida e integración servlet/reactiva |
| [Structures](axiz-framework/structures/README.md) | Organización layered, hexagonal, EDA, BFF y batch |
| [Extensions](axiz-framework/extensions/README.md) | Extensiones estructurales como CQRS |
| [Codegen](axiz-framework/codegen/README.md) | Motor compartido, catálogos y plantillas de generación |
| [Quality](axiz-framework/quality/README.md) | Reglas ArchUnit, pruebas de contratos y estándares de código |

En un servicio hexagonal, el dominio usa Java y los contratos de Axiz; los detalles de Spring,
HTTP, mensajería y persistencia permanecen en los adaptadores y en la composición de la
aplicación. Las capacidades incluyen dinero y redondeo explícito, seguridad, secretos,
auditoría de negocio, observabilidad, HTTP/SOAP, eventos, outbox/inbox, idempotencia, locks,
secuencias, almacenamiento, notificaciones y sagas.

## Requisitos

- JDK 25 y los wrappers Maven incluidos en cada proyecto.
- Docker activo para dependencias locales y pruebas de integración.
- Python 3 y Node, en las versiones y locks indicados por los workflows, para documentación y CI.
- GraalVM 25 cuando se construyan ejecutables nativos.

Los comandos siguientes funcionan en Linux y Fedora WSL. Las dependencias Maven se obtienen
del repositorio configurado en `settings.xml`; las credenciales se suministran desde el entorno
o el gestor de secretos.

## Compilar desde las fuentes

Ejecutar desde la raíz del repositorio:

```bash
(cd axiz-framework && ./mvnw --bootstrap clean install -Pquality -Daxiz.it.requireDocker=true)
(cd axiz-tools && ./mvnw clean verify -Pquality)
(cd axiz-demo && ./mvnw clean verify -Pquality)
```

El bootstrap instala los BOM y parents antes de resolver el reactor. Herramientas y demo
consumen el framework instalado o los artefactos publicados en Maven. Cada README de proyecto
detalla los perfiles de calidad, integración y compilación nativa.

## Crear un servicio

Con la CLI instalada, consultar el catálogo y generar desde un contrato OpenAPI:

```bash
axiz catalog archetypes
axiz new payment-orders --archetype business --structure hexagonal \
  --persistence aurora --openapi payments.yaml -c outbox
cd payment-orders
axiz doctor
./mvnw verify
```

`axiz new -i` permite seleccionar los parámetros interactivamente. `axiz add` incorpora
capacidades y `axiz generate` crea componentes dentro de un proyecto. CLI e Initializr usan
el mismo motor y validan las combinaciones del catálogo. Consultar la
[guía de creación](axiz-docs/docs/empezar/index.md) y el
[catálogo completo](axiz-docs/docs/arquetipos/catalogo.md).

## Ejecutar y operar

La [demo](axiz-demo/README.md) incluye PostgreSQL, Valkey, Kafka, LocalStack, Keycloak,
WireMock y observabilidad mediante Docker Compose, además de ejemplos para kind.
Usa datos ficticios y configuración local; los entornos desplegados obtienen secretos desde
su proveedor configurado.

Build y deploy son procesos independientes. Build produce artefactos, SBOM, manifiestos y
firmas; deploy verifica y promueve esos mismos bytes por hash, sin recompilar. La documentación
de [CI/CD](.github/docs/pipelines.md) describe parámetros, identidades y credenciales.

## Guías

- [Arquitectura y organización](axiz-docs/docs/arquitectura/index.md).
- [Capacidades y configuración](axiz-docs/docs/capacidades/index.md).
- [Persistencia](axiz-docs/docs/guias/persistencia.md) y [eventos](axiz-docs/docs/guias/eventos.md).
- [Seguridad](axiz-docs/docs/guias/seguridad.md) y [datos sensibles](axiz-docs/docs/guias/datos-sensibles.md).
- [Pruebas](axiz-docs/docs/guias/pruebas.md) y [reglas de arquitectura](axiz-docs/docs/calidad/arquitectura.md).
- [Publicación de versiones](RELEASING.md) y [documentación en GitHub Pages](axiz-docs/README.md).
