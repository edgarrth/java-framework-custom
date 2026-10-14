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
