<!-- GENERADO por scripts/sync_reference.py desde axiz-framework: no editar -->
# axiz-parent-tooling

Parent de las herramientas de build, los recursos de arquetipos y los generadores de código. Estos
artefactos pueden usar librerías de parseo, renderizado y formato. Están fuera de la capa core de
producción.

Conserva JSpecify, JUnit, los controles de calidad y la nomenclatura de módulos de las convenciones
de librerías. `axiz-parent-core` conserva su lista cerrada de dependencias de producción permitidas.
Las librerías de aplicación deben seleccionar el parent adecuado a su capa de arquitectura; tooling no
es una vía para eludir las reglas del núcleo.
