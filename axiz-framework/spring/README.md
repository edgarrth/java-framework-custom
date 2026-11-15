# Spring: capa 3

Integración Spring Boot 4.1 para las capacidades del framework.

- `axiz-<capacidad>-spring-boot-autoconfigure` registra beans mediante
  `AutoConfiguration.imports`, propiedades tipadas y condiciones de activación.
- `axiz-<capacidad>-spring-boot-starter` agrupa las dependencias que declara el servicio.
- Las fachadas seleccionan el modelo servlet o reactivo y coordinan transacciones, métricas
  y cierre de recursos.

Las capacidades se comunican mediante puertos core. Un starter no depende de otro starter;
las aplicaciones importan contratos y fachadas, sin importar clases de autoconfiguración.
Las propiedades `axiz.<capacidad>.*` y las condiciones para reemplazar beans se describen en
el README de cada módulo y en el [catálogo](../../axiz-docs/docs/capacidades/index.md).
