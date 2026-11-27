# Contribuciones

Estas reglas aplican a todos los componentes del repositorio: framework, herramientas, documentación,
demo y workflows compartidos. Mantienen el repositorio seguro para servicios bancarios de producción.

## Antes de empezar

- Las capacidades nuevas, los cambios incompatibles y las dependencias de terceros nuevas se discuten
  primero en un issue. Los cambios de API pública y las dependencias nuevas requieren revisión de un
  code owner, documentación de uso actualizada y las comprobaciones de compatibilidad e integración
  correspondientes.
- Nunca se hace commit de secretos, datos reales de clientes ni endpoints de producción. Los ejemplos
  locales usan solo los marcadores del ambiente local. El secret scanning de GitHub con push
  protection bloquea un secreto antes de que llegue al repositorio, y el hook de gitleaks de
  `.pre-commit-config.yaml` lo detecta antes del commit.
- No se agregan librerías deprecadas ni APIs o versiones que aún no se han publicado.

## Ramas y commits

- `main` contiene el código publicado. El trabajo ocurre en ramas de vida corta; `develop`, `release/*`
  y `hotfix/*` ejecutan el mismo CI que `main`.
- Los mensajes de commit y los títulos de los pull requests siguen Conventional Commits (`feat:`, `fix:`,
  `refactor:`, `docs:`, etc.). release-please deriva de ellos las versiones y los changelogs; un cambio
  incompatible se marca con `!` y un pie `BREAKING CHANGE:`. Ver [RELEASING.md](../RELEASING.md).
- Los commits se firman (GPG, SSH o S/MIME) y las ramas están protegidas: sin push directo a `main` ni a
  `develop`, historial lineal y comprobaciones obligatorias en verde.
- En WSL con `core.filemode=false`, los wrappers y los puntos de entrada de shell nuevos o renombrados
  se agregan con `git -c core.filemode=true add --all` para que su bit de ejecución llegue al commit. La
  comprobación de entradas del build verifica el índice.

## Pull requests

- Cada cambio llega con pruebas que habrían fallado sin él. Los controles de calidad exigen cobertura
  (líneas y ramas), pruebas de mutación en el núcleo, reglas de arquitectura y análisis estático.
- Las advertencias se corrigen, no se suprimen. Una supresión necesita un comentario que explique por
  qué el código es correcto (por ejemplo `@SuppressWarnings("EqualsGetClass")` con su motivo).
- Los tipos sin ambigüedad se importan en lugar de calificarlos en línea. La comprobación
  `UnnecessarilyFullyQualified` de Error Prone usa `WARN` por defecto; los módulos depurados fijan
  `axiz.errorprone.fqn.severity` en `ERROR`. La calificación se mantiene cuando los nombres colisionan y
  las fuentes generadas quedan a cargo de su generador.
- El `README.md` del módulo y el sitio de documentación se actualizan cuando cambia el comportamiento o
  el uso. Las APIs públicas llevan Javadoc.
- Revisiones: al menos una aprobación de un code owner; dos aprobaciones (una del equipo de seguridad)
  para cambios de seguridad, criptografía, autenticación, flujos de pago y CI/CD.

## Comprobaciones locales

| Componente | Comando |
|---|---|
| Todo | `scripts/build.sh` desde la raíz del repositorio |
| `axiz-framework` | `./mvnw --bootstrap -B -ntp clean verify -Pquality` |
| `axiz-tools` | `./mvnw -B -ntp clean verify -Pquality` |
| `axiz-demo` | `./mvnw -B -ntp clean verify` y `scripts/test-all.sh` con la demo levantada |
| `axiz-docs` | `mkdocs build --strict` |
| `.github` | `actionlint` y `zizmor .github/workflows` |

`axiz-framework/.github/scripts/samples.sh` genera los proyectos de ejemplo por defecto del
catálogo; `MODE=all` genera la matriz completa de estructuras, extensiones, stacks y persistencia.
`reactive-native-sample.sh` requiere GraalVM JDK 25 y Docker: genera un servicio reactivo, ejecuta
sus pruebas en la JVM y luego compila la imagen nativa con sus pruebas nativas.

## Compatibilidad

Las APIs y SPIs públicas siguen versionado semántico. japicmp compara cada módulo con la última
versión publicada; ver la [política de API](../axiz-framework/docs/api/README.md). Los cambios se documentan en
el README del módulo y se aporta una receta de OpenRewrite cuando la transformación puede automatizarse.
