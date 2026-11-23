<!-- generated -->
# axiz-i18n-spring-boot-autoconfigure

Paquetes de mensajes, resolución de Accept-Language y localización de mensajes de error (autoconfiguración).

## Dependencia

```xml
<dependency>
  <groupId>pe.axiz</groupId>
  <artifactId>axiz-i18n-spring-boot-autoconfigure</artifactId>
</dependency>
```

La versión la gestiona `axiz-bom` (los servicios heredan `axiz-parent-service`).

## Tipos principales

| Tipo | Propósito |
|---|---|
| `AcceptLanguageLocaleContextResolver` | Resolvedor de locale reactivo basado solo en `Accept-Language`. |
| `AcceptLanguageLocaleResolver` | Resolvedor de locale servlet basado solo en `Accept-Language`; el locale no se puede cambiar. |
| `AxizI18nAutoConfiguration` | Paquetes de mensajes y manejo de `Accept-Language`. |
| `AxizI18nProperties` | Configuración de localización (`axiz.i18n.*`). |

## Pruebas

Las pruebas unitarias están en `src/test/java` y documentan el comportamiento esperado; se ejecutan con `./mvnw -pl spring/axiz-i18n-spring-boot-autoconfigure verify`.
