# java-framework-custom

Monorepo de Axiz para desarrollar servicios backend financieros en Java 25 y Spring Boot 4.1.
Repositorio: `edgarrth/java-framework-custom`. La primera versión pública del framework es 2.0.0.

## Componentes

| Directorio | Responsabilidad |
|---|---|
| [axiz-framework](axiz-framework) | Contratos, algoritmos, adaptadores, kits de persistencia, integración Spring, estructuras, generación y calidad |
| [axiz-tools](axiz-tools) | CLI, Initializr y recetas de actualización |
| [axiz-demo](axiz-demo) | Demostración de pagos y su infraestructura local |
| [.github](.github) | Workflows ejecutables, acciones compartidas, reglas de entrega y configuración de actualizaciones |
| [axiz-docs](axiz-docs) | Guías y sitio de documentación |

Las carpetas son componentes del mismo repositorio. Las releases de framework, herramientas, documentación y workflows tienen versiones independientes y tags por componente (`axiz-framework-v2.0.0`, `axiz-tools-v2.0.0`, `axiz-docs-v2.0.0`, `axiz-workflows-v2.0.0`). Release Please se configura una sola vez en la raíz.

## Arquitectura del framework

- Platform gobierna dependencias y builds; `axiz-schema` distribuye el DDL canónico como recursos.
- Core contiene los contratos y algoritmos, sin Spring ni clientes de tecnología.
- Adapters implementa los puertos con clientes nativos; kits expone deliberadamente los tipos de persistencia.
- Spring configura lifecycle, transacciones, seguridad y las fachadas servlet/reactivas.
- Structures define la organización del servicio; extensions agrega capacidades de estructura opcionales.
- Codegen y quality generan proyectos y verifican sus contratos.


## Compilar y validar

Entorno de validación: Fedora WSL, JDK 25, Maven wrapper y Docker activo para integraciones. Los detalles y comandos de cada build están en los README de sus componentes.

```bash
cd axiz-framework
./mvnw --bootstrap clean install -Pquality -Daxiz.it.requireDocker=true
cd ../axiz-tools
./mvnw clean verify -Pquality
cd ../axiz-demo
./mvnw clean verify -Pquality
```

Los builds de herramientas y demo consumen el framework previamente instalado o un candidato verificado. El staging de release, sus hashes y su procedencia se verifican antes de promover. Build y deploy son workflows independientes; deploy consume artefactos ya construidos.

## Ejecutar el demo

Seguir el [README del demo](axiz-demo/README.md) para Docker Compose, escenarios, Bruno y kind. Las pruebas no deben borrar volúmenes existentes del desarrollador.

## Publicación y documentación

El procedimiento de primera publicación y los tags inmutables se documentan en [RELEASING.md](RELEASING.md). La documentación de capacidades está en [axiz-docs](axiz-docs). Los reportes de revisión distinguen implementación local, pruebas verificadas y publicación remota.
