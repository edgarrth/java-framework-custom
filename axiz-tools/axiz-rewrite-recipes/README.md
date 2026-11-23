# axiz-rewrite-recipes

Convenciones vigentes de Java, Spring y JSpecify y reparación del scope de los adaptadores de runtime,
invocadas mediante `pe.axiz.UpgradeToAxiz`. La versión 2.0.0 es la primera release de Axiz: ninguna
receta traduce selectores, nombres de artefactos ni APIs que no hayan sido publicados.

```bash
mvn org.openrewrite.maven:rewrite-maven-plugin:run \
  -Drewrite.recipeArtifactCoordinates=pe.axiz:axiz-rewrite-recipes:<version> \
  -Drewrite.activeRecipes=pe.axiz.UpgradeToAxiz
```

## Dependencias

Las recetas que referencia este artefacto provienen de `rewrite-spring` y `rewrite-migrate-java`
(versiones de `rewrite-recipe-bom`). Sus módulos difieren en algunas librerías compartidas, por lo que
el pom alinea ASM en la versión más reciente que piden mediante `asm-bom`; `error_prone_annotations` y
las anotaciones de JetBrains provienen de `axiz-dependencies`, como cualquier otra versión de terceros
(el control de gobierno del framework también cubre este repositorio). `rewrite-jackson` pide el
milestone 4.0.0-M1 de AssertJ: se excluye y en su lugar se declara la release gestionada de AssertJ,
porque la plataforma solo distribuye releases. Los parsers por versión de Java repiten dos clases
`package-info` (`org.openrewrite.java` y `org.openrewrite.java.isolated`); contienen solo anotaciones
de paquete, por lo que `banDuplicateClasses` ignora exactamente esas dos entradas y sigue comprobando
todas las demás clases de esos jars.
