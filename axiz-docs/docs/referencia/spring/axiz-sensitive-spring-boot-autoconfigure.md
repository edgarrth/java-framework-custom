<!-- GENERADO por scripts/sync_reference.py desde axiz-framework: no editar -->
# axiz-sensitive-spring-boot-autoconfigure

Enmascaramiento de campos @Sensitive y @Mask en respuestas JSON, logs y auditoría (autoconfiguración).

## Dependencia

```xml
<dependency>
  <groupId>pe.axiz</groupId>
  <artifactId>axiz-sensitive-spring-boot-autoconfigure</artifactId>
</dependency>
```

La versión la gestiona `axiz-bom` (los servicios heredan `axiz-parent-service`).

## Tipos principales

| Tipo | Propósito |
|---|---|
| `AxizSensitiveAutoConfiguration` | Enmascaramiento de datos sensibles en las tres salidas: respuestas de la API (el convertidor o encoder JSON de las respuestas del servidor), logs y auditoría (`ObjectMasker` compartido por los starters de logging y auditoría). |
| `AxizSensitiveProperties` | Política de enmascaramiento (`axiz.sensitive.*`). |
| `SensitiveJacksonModule` | Enmascara las propiedades anotadas con `Sensitive` o `Mask` en las respuestas JSON, según la política `OutputChannel#RESPONSE`. |

## Pruebas

Las pruebas unitarias están en `src/test/java` y documentan el comportamiento esperado; se ejecutan con `./mvnw -pl spring/axiz-sensitive-spring-boot-autoconfigure verify`.
