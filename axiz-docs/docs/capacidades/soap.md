<!-- GENERADO por scripts/sync_reference.py desde axiz-framework: no editar -->
# soap

Clientes SOAP 1.1/1.2 con WS-Security, timeouts y observaciones

```bash
axiz add soap
```

|  |  |
|---|---|
| Starter | `pe.axiz:axiz-soap-spring-boot-starter` |
| Stacks del catálogo | servlet |
| Agrega también | [`secrets`](secrets.md) |
| Incluida por defecto en | ningún arquetipo |

## Propiedades que agrega

Valores por defecto seguros en `application.yaml`; los valores de cada ambiente van en el ConfigMap del servicio y los secretos en el secret store.

```yaml
axiz.soap.clients.example.endpoint: ${EXAMPLE_SOAP_ENDPOINT:https://example.invalid/soap}
```

## Llaves del ConfigMap por ambiente

- `axiz.soap.clients`

## Uso

```java
Element reply = soapClients.call("example", "urn:getBalance", requestXml);
```

## Módulos

- [`axiz-soap-spring-boot-autoconfigure`](../referencia/spring/axiz-soap-spring-boot-autoconfigure.md)
- [`axiz-core-commons`](../referencia/core/axiz-core-commons.md)
- [`axiz-core-observability`](../referencia/core/axiz-core-observability.md)
- [`axiz-core-secrets`](../referencia/core/axiz-core-secrets.md)
- [`axiz-core-soap`](../referencia/core/axiz-core-soap.md)
