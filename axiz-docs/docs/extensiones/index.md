# Extensiones

Una extensión complementa la estructura elegida. Actualmente está disponible
[CQRS](cqrs.md), exclusivamente sobre hexagonal para `business` o `event-processor`.

```sh
axiz new payment-orders --archetype business --stack servlet --structure hexagonal \
  --extension cqrs --persistence aurora --openapi ./openapi.yaml
```

El manifiesto schema 2 mantiene selectores separados:

```yaml
schema: 2
archetype: business
stack: servlet
structure: hexagonal
extensions: [cqrs]
```

CQRS organiza comandos y consultas, handlers y behaviors. No reemplaza el arquetipo,
no decide el almacén y no añade una segunda estructura al servicio.
