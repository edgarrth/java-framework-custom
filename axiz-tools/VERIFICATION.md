# Verificación de esta entrega

Sin acceso a Maven Central en el entorno de construcción; `mvn verify` corre en CI.

| Módulo | Resultado local |
|---|---|
| `axiz-cli` | compila; 17 pruebas OK (new, add, generate, contract sync/lint/diff, upgrade, doctor, local, catalog) contra el motor real del framework |
| `axiz-initializr` | compila código y pruebas; las pruebas web con contexto de Spring (MockMvc, seguridad, publicación en GitHub) corren en CI |
| `axiz-rewrite-recipes` | omitido: necesita `rewrite-migrate-java` y `rewrite-spring` |
| Chart del initializr | kubeconform estricto y kube-linter sin hallazgos en dev, qa y prod |
| Workflows | actionlint + shellcheck sin hallazgos |

La salida de `axiz <comando> --help` usada en `axiz-docs` se obtuvo de esta misma CLI.
