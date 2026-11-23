<!-- generated -->
# axiz-code-standards

Configuración de Checkstyle, SpotBugs, Semgrep, Spectral y gitleaks.

## Dependencia

```xml
<dependency>
  <groupId>pe.axiz</groupId>
  <artifactId>axiz-code-standards</artifactId>
</dependency>
```

La versión la gestiona `axiz-bom` (los servicios heredan `axiz-parent-service`).

## Error Prone

El perfil de calidad del parent activa `UnnecessarilyFullyQualified` como advertencia junto con
Error Prone y NullAway. Se usan imports cuando no hay colisión de nombres de tipo. Los nombres
calificados siguen siendo adecuados cuando JDBC y R2DBC exponen tipos con el mismo nombre simple.

## SpotBugs

El `exclude.xml` general está vacío. Los falsos positivos del código escrito a mano del framework
usan un `@SuppressFBWarnings` justificado sobre el miembro aplicable más pequeño. Las advertencias de
prioridad media y alta bloquean el control de calidad.

Solo el parent de servicios usa `generated-dtos.xml`: permite la API de colecciones mutables de los
modelos de transporte OpenAPI que llevan exactamente la marca `pe.axiz.core.commons.Generated`. Solo
`EI_EXPOSE_REP` y `EI_EXPOSE_REP2` quedan exentos; las advertencias de seguridad y de constructores
siguen activas. El verificador de encapsulamiento rechaza ampliar esta excepción a otros detectores o
anotaciones.
