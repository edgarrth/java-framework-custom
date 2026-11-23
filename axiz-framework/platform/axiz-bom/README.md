# axiz-bom

Lista de materiales (BOM) de los artefactos de axiz: todos los módulos `pe.axiz` con la misma versión.
Los servicios que no pueden heredar `axiz-parent-service` la importan (junto con `axiz-dependencies`) en
`dependencyManagement`:

```xml
<dependency>
  <groupId>pe.axiz</groupId>
  <artifactId>axiz-bom</artifactId>
  <version>${axiz.version}</version>
  <type>pom</type>
  <scope>import</scope>
</dependency>
```
