<!-- GENERADO por scripts/sync_reference.py desde axiz-framework: no editar -->
# axiz-parent-service

Parent de todos los microservicios: repackage de Spring Boot, imagen Jib (sin root, etiqueta inmutable;
el perfil `native` añade la imagen GraalVM `<tag>-native`), generación de modelos contract-first desde
`src/main/openapi/openapi.yaml`, el perfil `quality` (SpotBugs/FindSecBugs, umbrales de JaCoCo),
pruebas de integración con Failsafe y los estándares de código de `axiz-code-standards`.

```xml
<parent>
  <groupId>pe.axiz</groupId>
  <artifactId>axiz-parent-service</artifactId>
  <version>2.0.0</version>
  <relativePath/>
</parent>
```

Propiedades que un servicio puede sobrescribir: `axiz.image.registry`, `axiz.image.base`,
`axiz.image.native-base`, `axiz.image.tag`.

## Compilación nativa

Con GraalVM JDK 25 seleccionado, se ejecuta `./mvnw -Pnative package native:compile`.
El compilador nativo lee las clases AOT desde `target/classes`, mientras Spring Boot conserva la
estructura del JAR ejecutable para la ejecución en la JVM. El perfil selecciona el DRBG del JDK durante
el análisis de la imagen; la infraestructura reactiva comprueba ese valor por defecto y lo precalienta
al arrancar la aplicación.
