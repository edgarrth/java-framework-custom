<!-- GENERADO por scripts/sync_reference.py desde axiz-framework: no editar -->
# Generación de proyectos

CLI e Initializr usan el mismo motor para producir proyectos coherentes con el framework.

| Módulo | Responsabilidad |
|---|---|
| [axiz-codegen](axiz-codegen.md) | Validación de solicitudes, contratos, proyectos y componentes |
| [axiz-codegen-templates](axiz-codegen-templates.md) | Plantillas y manifiestos de los archivos que se generan |
| [axiz-archetypes](axiz-archetypes.md) | Catálogos de roles, estructuras y capacidades compatibles |

La solicitud selecciona arquetipo, estructura, extensiones, stack, persistencia y capacidades.
El generador valida nombres, rutas y combinaciones antes de escribir el proyecto. Los contratos
OpenAPI producen modelos durante el build y esqueletos de controladores una sola vez. Los
contratos AsyncAPI describen eventos y handlers.

`axiz add` incorpora capacidades y `axiz generate` crea componentes en la organización elegida.
Los servicios incluyen configuración local, contratos, pruebas de arquitectura, chart Helm y
workflows independientes de build y deploy. El README generado describe los comandos y las
capacidades activadas.

Consultar la [guía de creación](../../empezar/index.md) y los README de
[CLI e Initializr](https://github.com/edgarrth/java-framework-custom/blob/main/axiz-tools/README.md). Desde la raíz del framework,
`./mvnw -pl codegen -am verify` ejecuta las pruebas del generador y de sus recursos.
