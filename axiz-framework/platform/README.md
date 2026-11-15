# Platform

Configuración Maven y recursos compartidos del framework.

| Módulo | Uso |
|---|---|
| [axiz-dependencies](axiz-dependencies/README.md) | Versiones de dependencias externas y plugins |
| [axiz-bom](axiz-bom/README.md) | Versiones coherentes de los módulos Axiz |
| `axiz-parent-*` | Reglas de dependencias, compilación y calidad para cada capa y para los servicios |
| [axiz-schema](axiz-schema/README.md) | Fragmentos SQL canónicos consumidos por adaptadores y generación |

Los servicios heredan `axiz-parent-service`; los módulos del framework usan el parent de su
capa. Los parents aplican Enforcer y los perfiles de calidad. Las versiones se administran en
los BOM; los módulos no fijan versiones externas de forma independiente.

Para instalar los BOM y parents desde fuentes, ejecutar `./mvnw --bootstrap install` desde
`axiz-framework`. Las credenciales y mirrors Maven pertenecen a `settings.xml` o al entorno.
