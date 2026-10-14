# Contrato primero

El contrato es la fuente de verdad: **OpenAPI 3.1** para la API REST y **AsyncAPI 3** para los
eventos. El código se deriva de él, nunca al revés.

## Qué se genera y cuándo

| Qué | Cuándo | Versionado |
|---|---|---|
| Modelos de request y response (`<base>.api.model`) | en cada build (openapi-generator, solo modelos) | no |
| Controladores, uno por tag, un método por `operationId` | una vez, al crear el servicio o con `contract sync` | sí: es tu código |
| Casos de uso, commands/queries o composiciones de cada operación | igual que los controladores | sí |
| Clientes de otras APIs (`axiz generate http-client`) | cuando lo pides | sí (interfaz y modelos) |

Los controladores esqueleto delegan en lo que manda el estilo y responden `501 Not Implemented`
hasta que implementas la operación. Por qué no se generan interfaces y delegates en cada build:

## Reglas del contrato

`axiz contract lint` (y Spectral con el ruleset de axiz en CI):

| Regla | Qué pide |
|---|---|
| `axiz-operation-id-camel-case` | `operationId` en lowerCamelCase (nombra el caso de uso o handler) |
| `axiz-paths-kebab-case` | segmentos de ruta en kebab-case |
| `axiz-versioned-paths` | rutas que empiezan con la versión (`/v1/...`) |
| `axiz-problem-json-errors` | respuestas 4xx/5xx en `application/problem+json` |
| `axiz-examples-required` | ejemplos en cuerpos de request y response |
| `axiz-no-business-ids-in-query` | ningún documento ni tarjeta como query parameter |
| `axiz-pagination-params` | `page` y `size` en los GET de colecciones |
| `axiz-idempotency-key-on-post` | `Idempotency-Key` en los POST no idempotentes |

## Evolucionar sin romper

```bash
axiz contract sync
axiz contract diff contracts/v1.yaml contracts/v1-next.yaml
```

`sync` compara el contrato con la última versión sincronizada (`.axiz/openapi.snapshot.yaml`):

- **Operaciones nuevas**: genera su controlador o método y su caso de uso.
- **Operaciones existentes que cambiaron**: escribe una propuesta junto al archivo
  (`*.axiz-new`) sin tocar tu código; `axiz doctor` avisa mientras quede una sin resolver.
- **Operaciones borradas**: se reportan.
- **Cambios incompatibles**: bloquean el sync salvo con `--allow-breaking`.

Desde el punto de vista de los consumidores existentes son incompatibles: borrar operaciones, nuevos
parámetros o campos requeridos, quitar o cambiar el tipo de campos de respuesta y cambiar valores
de enums. Son compatibles: operaciones nuevas, entradas opcionales y campos de respuesta nuevos.
En CI, `oasdiff` compara el contrato contra la rama destino en cada PR.

## Contratos de terceros

Los contratos de sistemas externos se guardan en el servicio que los consume (en el demo,
`contracts/external/core-banking.yaml`, `payment-network.yaml` y el WSDL del core de créditos) y se
generan clientes con solo sus modelos. El adaptador traduce esos modelos y errores al dominio, así
un cambio del proveedor queda contenido en un paquete.

## Pruebas de contrato

`ContractExamplesTest` valida cada ejemplo de request del contrato contra los modelos generados y
sus restricciones (Bean Validation); la colección de Bruno del demo ejercita los mismos ejemplos
contra los servicios corriendo.
