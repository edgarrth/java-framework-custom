<!-- GENERADO por scripts/sync_reference.py desde axiz-framework: no editar -->
# axiz-coverage-report

Agrega los reportes de JaCoCo de todos los módulos con código de producción (`./mvnw -Pquality verify`,
reporte en `target/site/jacoco-aggregate`). No se publica.

- Cada módulo con código es una dependencia `provided`: el `report-aggregate` de JaCoCo toma clases y
  fuentes solo de las dependencias compile, runtime y provided. Una dependencia `test` aporta sus
  datos de ejecución sin nada contra qué medirlos, lo que daría un reporte vacío.
- Los módulos solo de pruebas de arquitectura y de integración de almacenes son dependencias `test`,
  para que sus datos de ejecución cuenten para los módulos de producción que ejercitan, sin contar los
  fixtures de prueba como producción.
- `.github/scripts/check-coverage-aggregate.py .` (CI del framework) falla cuando falta un módulo con
  código o tiene otro scope, o cuando se omite un módulo solo de pruebas; con `-Pquality` el build falla
  cuando el reporte no contiene clases. `-DskipTests` omite esa comprobación, porque nada se ejecutó
  bajo el agente.
