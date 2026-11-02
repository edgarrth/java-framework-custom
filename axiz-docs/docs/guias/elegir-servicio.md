# Elegir arquetipo, stack, estructura y extensiones

Empieza por la responsabilidad: `business` posee reglas y datos; `core` traduce un sistema
interno; `gateway` integra un tercero; `ux` compone para un canal; `support` ofrece una capacidad
transversal; `event-processor` reacciona a eventos; `batch` procesa lotes.

Después elige el stack completo. Servlet sirve para código secuencial con JPA/JDBC o clientes
síncronos. Reactive requiere que todas las capacidades elegidas utilicen I/O asíncrona nativa;
no se permite envolver un puerto síncrono en `boundedElastic`.

La estructura decide paquetes y dependencias. Hexagonal separa dominio, aplicación y adaptadores;
layered organiza controller/service/repository; bff compone; eda organiza reacciones; batch define
jobs. CQRS es una extensión de hexagonal, disponible para business y event-processor.

```sh
axiz new payment-orders --archetype business --stack servlet --structure hexagonal \
  --extension cqrs --persistence aurora --group pe.axiz.payments \
  --package pe.axiz.payments.orders --openapi ./payment-orders.yaml
```

El contrato HTTP se prepara antes de generar un servicio REST y declara
`info.x-axiz-archetype: business`. Para un procesador, el contrato es AsyncAPI:

```sh
axiz new payment-projections --archetype event-processor --structure hexagonal \
  --stack reactive --persistence aurora --extension cqrs --asyncapi ./payment-events.yaml
```

El generador, `axiz add` y `axiz doctor` comparten el catálogo y rechazan combinaciones
incompatibles. El [catálogo generado](../arquetipos/catalogo.md) detalla las opciones por rol.
El nombre describe la capacidad y no repite el arquetipo.
