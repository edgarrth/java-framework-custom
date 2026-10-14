# Verificación de esta entrega

- actionlint 1.7 con shellcheck 0.11 sobre todos los workflows: sin hallazgos.
- shellcheck sobre los scripts de las acciones compuestas: sin hallazgos.
- Todas las acciones de terceros están fijadas por SHA de commit (resueltos contra GitHub el
  2026-10-11) con la versión en comentario; `self-check` lo exige y Renovate las mantiene.
- `actions/gitops-update` probado contra un repositorio Git local: escribe el release, no crea
  commits vacíos al repetirse y rechaza entradas inválidas (rutas con `..`, digest o SHA mal formados).
- `gitops/` (ApplicationSet, AppProject, política de Kyverno): YAML válido; su esquema se valida en
  `self-check` con el catálogo de CRDs.
- No verificable fuera de GitHub: ejecución real de los workflows (OIDC, Nexus, SonarQube, Argo CD).
