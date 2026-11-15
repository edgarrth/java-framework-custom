# Repositorios y versiones

Un único repositorio contiene cuatro componentes con versiones independientes y la demo.

| Directorio | Contenido | Versión y tag |
|---|---|---|
| `axiz-framework` | Plataforma, capas 1 a 4, motor de generación y plantillas, arquetipos, estándares de código, pruebas de arquitectura | Única para todos sus módulos (`${revision}`); `axiz-framework-vX.Y.Z` |
| `.github` | Workflows reutilizables, acciones compuestas, presets de Renovate, referencias GitOps | `axiz-workflows-vX.Y.Z`, inmutable |
| `axiz-tools` | `axiz-initializr`, `axiz-cli`, recetas de OpenRewrite | Propia; `axiz-tools-vX.Y.Z` |
| `axiz-docs` | Este sitio, guías de uso y actualización, dashboards de Grafana | Propia; `axiz-docs-vX.Y.Z` |
| `axiz-demo` | Servicios demo de todos los arquetipos y su entorno local | Sigue la versión del framework; no se publica |

Las herramientas y el demo consumen el BOM publicado, igual que un servicio real.

## Parents y BOMs

`axiz-parent` es el `pom.xml` raíz del framework: no contiene código y ningún servicio lo usa.
Agrega todos los módulos y define una sola vez lo compartido (Java 25, plugins y versiones,
enforcer, formato, análisis estático, publicación). De él heredan los tres parents publicados:

| Parent | Quién lo hereda | Qué agrega |
|---|---|---|
| `axiz-parent-core` | módulos de la capa 1 y librerías Java puras de los equipos | Prohíbe Spring y Quarkus; sin Boot |
| `axiz-parent-spring` | starters, frameworks y librerías Spring de los equipos | Gestión de Spring Boot, metadata de propiedades, hints AOT; sin fat JAR |
| `axiz-parent-service` | microservicios | Repackage, imagen con Jib (o nativa), perfiles de gates, pruebas de integración, generación de modelos desde el contrato |

Los BOMs son distintos de los parents: un parent dice **cómo** se construye y se hereda; un BOM
dice **qué versiones** usar y se importa. `axiz-dependencies` fija las versiones de terceros (el
único lugar donde se decide una) y `axiz-bom` las de los artefactos `pe.axiz`. Un servicio con su
propio parent puede importar ambos sin heredar nada.

## Ramas, versiones y releases

- `main` contiene lo liberado; `develop`, `release/*` y `hotfix/*` se construyen como `main`; las
  ramas de trabajo solo corren los checks del PR.
- Versionado SemVer con un tren alineado a la minor de Spring Boot; se soportan la versión actual y
  la minor anterior.
- release-please mantiene un PR de release por componente con la próxima versión y el changelog
  (Conventional Commits). Al hacer merge crea el tag del componente; el deploy promueve a Nexus los
  artefactos del build verificado.
- Toda API obsoleta se marca `@Deprecated(forRemoval = true)` con al menos una minor de aviso, y
  cada release trae sus recetas de OpenRewrite (ver [Actualizar servicios](../guias/actualizar-servicio.md)).
- Nexus Repository es el repositorio de artefactos Maven e imágenes; las credenciales vienen de los
  secretos de CI o de `~/.m2/settings.xml`, nunca de un repositorio.

## Gobierno

El equipo de plataforma es dueño del framework (`.github/CODEOWNERS`). Una capacidad nueva se
propone en un issue, se construye con el arquetipo `starter` (`axiz new axiz-<capacidad> --archetype starter`)
y llega con su caso de uso en el demo, que una prueba de cobertura obliga a mantener.
