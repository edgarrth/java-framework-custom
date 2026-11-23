<!-- GENERADO por scripts/sync_reference.py desde axiz-framework: no editar -->
# axiz-adapter-sequence-r2dbc

Reservas nativas de contadores en PostgreSQL para servicios reactivos. Implementa `ReactiveSequenceStore`
con cancelación de Reactor y propagación de contexto. Cada reserva usa su propia conexión y transacción,
hace commit antes de emitir el valor y sobrevive a un rollback posterior del negocio.

Es un adaptador de tecnología, no un kit general de persistencia. Los consumidores lo seleccionan en tiempo
de ejecución mediante la capacidad de secuencias con R2DBC; el starter genérico de secuencias no impone este backend.

Se ejecuta `./mvnw --bootstrap -pl adapters/axiz-adapter-sequence-r2dbc -am verify -Daxiz.it.requireDocker=true` desde la raíz del framework. La API pública se revisa junto con los demás módulos publicados mediante el control de firmas.
