<!-- GENERADO por scripts/sync_reference.py desde axiz-framework: no editar -->
# axiz-kit-audit-reactor

Contrato Reactor, independiente del proveedor, para el estado transaccional de la cadena de auditoría.

Este módulo es dueño de la API `pe.axiz.kit.reactor.audit`.

## Contrato

`ReactiveAuditChainState` se une a la transacción del suscriptor. `advance` exige una transacción
activa, bloquea la cabeza seleccionada y aplica un callback puro de CPU. Un rollback debe restaurar
la cabeza anterior. El llamador debe escribir el registro de auditoría asociado antes del commit.

`transactionResource()` devuelve la identidad exacta del recurso prestado que usa el escritor del
outbox; no es una conexión, ni una transacción transferible, ni la propiedad de un pool. Una
identidad nula no puede garantizar atomicidad y la composición durable del framework la rechaza.

`checkpoint` lee el estado confirmado mediante una conexión independiente. No debe reutilizar la
conexión sin confirmar del suscriptor. Su resultado es una referencia de la base de datos, no una
prueba de que un destino remoto recibió los registros del outbox.

El kit no tiene binding de Spring, driver de base de datos, planificador ni adaptador imperativo.
La implementación para PostgreSQL y el comportamiento de los timeouts pertenecen a
`axiz-adapter-outbox-r2dbc`.
