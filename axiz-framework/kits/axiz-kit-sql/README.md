# axiz-kit-sql

Validación de identificadores SQL independiente de los drivers de base de datos.

Este módulo es dueño de la API `pe.axiz.kit.sql`. No depende de frameworks de aplicación ni de proveedores.

## Selección

Las versiones las gestiona `axiz-bom`; las aplicaciones heredan `axiz-parent-service`.

```xml
<dependency>
  <groupId>pe.axiz</groupId>
  <artifactId>axiz-kit-sql</artifactId>
</dependency>
```

## Tipos

Tipos públicos que declara este módulo:

- `SqlIdentifiers`

## Verificación

Desde `axiz-framework`:

```bash
./mvnw -pl kits/axiz-kit-sql -am test
```

La verificación completa es `./mvnw clean install -Pquality -Daxiz.it.requireDocker=true`.
Las pruebas de integración de proveedores están en `quality/axiz-store-integration-tests` y requieren Docker.
