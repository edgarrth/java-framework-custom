<!-- generated -->
# axiz-core-i18n

Resolución de mensajes desde varios paquetes con respaldo de locale.

## Dependencia

```xml
<dependency>
  <groupId>pe.axiz</groupId>
  <artifactId>axiz-core-i18n</artifactId>
</dependency>
```

La versión la gestiona `axiz-bom` (los servicios heredan `axiz-parent-service`).

## Tipos principales

| Tipo | Propósito |
|---|---|
| `BundleMessageResolver` | Resolvedor de mensajes sobre varios nombres base de `ResourceBundle` (properties en UTF-8). |
| `CompositeMessageResolver` | Encadena resolvedores; gana el primero que conoce la clave (el servicio antes que el framework). |
| `LocaleResolver` | Elige el locale de la respuesta desde la cabecera `Accept-Language` entre los soportados (búsqueda RFC 4647 con valores de calidad). |
| `MessageFormatter` | Reemplaza los marcadores `{name`}. |
| `MessageResolver` | Resuelve mensajes localizados con parámetros nombrados (`{name}`). |

## Pruebas

Las pruebas unitarias están en `src/test/java` y documentan el comportamiento esperado; se ejecutan con `./mvnw -pl core/axiz-core-i18n verify`.
