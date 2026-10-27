# axiz-rewrite-recipes

OpenRewrite recipes that `axiz upgrade` applies to a service when it moves to a newer framework
version (parent, renamed properties and types, API changes). Each release adds its migration to
the chain `pe.axiz.UpgradeToAxiz`; the version of the recipes matches the framework version they
upgrade to.

```bash
mvn org.openrewrite.maven:rewrite-maven-plugin:run \
  -Drewrite.recipeArtifactCoordinates=pe.axiz:axiz-rewrite-recipes:<version> \
  -Drewrite.activeRecipes=pe.axiz.UpgradeToAxiz
```

## Dependencies

The recipes this artifact references come from `rewrite-spring` and `rewrite-migrate-java`
(versions from `rewrite-recipe-bom`). Their modules disagree on a few shared libraries, so the pom
aligns ASM on the newest version they ask for through `asm-bom`; `error_prone_annotations` and
JetBrains annotations come from `axiz-dependencies`, like every other third-party version (the
governance check of the framework covers this repository too). `rewrite-jackson` asks for the
AssertJ 4.0.0-M1 milestone: it is excluded and the managed AssertJ release is declared instead,
because the platform ships releases only. The per-Java-version parsers repeat two `package-info`
classes (`org.openrewrite.java` and `org.openrewrite.java.isolated`); they hold only package
annotations, so exactly those two entries are ignored by `banDuplicateClasses` and every other
class of those jars is still checked.
