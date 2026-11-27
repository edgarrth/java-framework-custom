<!-- generated -->
# axiz-core-soap

Soporte ligero para clientes SOAP 1.1 y 1.2: sobres, WS-Security UsernameToken y mapeo de faults.

## Dependencia

```xml
<dependency>
  <groupId>pe.axiz</groupId>
  <artifactId>axiz-core-soap</artifactId>
</dependency>
```

La versión la gestiona `axiz-bom` (los servicios heredan `axiz-parent-service`).

## Tipos principales

| Tipo | Propósito |
|---|---|
| `SafeXml` | Parseo XML protegido contra XXE y expansión de entidades, más utilidades de serialización. |
| `SoapClient` | Cliente SOAP mínimo sobre `HttpClient` con timeouts obligatorios, WS-Security, mapeo de faults y logs enmascarados. |
| `SoapEnvelope` | Construye sobres SOAP alrededor de un fragmento XML de payload. |
| `SoapException` | Fallo técnico de SOAP (XML mal formado o serialización). |
| `SoapResponse` | Procesa respuestas SOAP: extrae el payload del cuerpo o convierte un Fault en un error externo. |
| `SoapVersion` | Versiones del protocolo SOAP. |
| `WsSecurity` | Cabecera WS-Security UsernameToken. |

## Pruebas

Las pruebas unitarias están en `src/test/java` y documentan el comportamiento esperado; se ejecutan con `./mvnw -pl core/axiz-core-soap verify`.
