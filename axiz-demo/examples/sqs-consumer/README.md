# Consumidor SQS con descubrimiento del starter

Este ejemplo independiente demuestra el contrato de `axiz-messaging-sqs-spring-boot-starter`
en aplicaciones servlet y reactivas. `ConsumerApplication` usa `@EnableAutoConfiguration`;
Spring descubre los proveedores mediante `AutoConfiguration.imports` desde el classpath mínimo.
No se enumeran auto-configuraciones ni se simula el broker.

`SqsConsumerExampleTest` crea colas efímeras en LocalStack y demuestra:

- Publicación y consumo con `AxizEvents` / `ReactiveEvents` y handlers tipados.
- ACK tras completar el handler; fallo transitorio con reintento acotado.
- Rechazo permanente a una DLQ explícita y redrive administrado por SQS.
- FIFO: el `subject` define el grupo y el ID del evento la deduplicación; dos eventos del
  mismo grupo llegan en orden y repetir el primero no duplica su procesamiento.
- Cierre del consumidor al cerrar el contexto. La persistencia de negocio y su inbox se
  muestran en `payments-history`; este ejemplo mantiene el recibo en memoria para observar
  el protocolo del broker, por lo que no promete procesamiento exactamente una vez.

La configuración se construye junto a las colas en el test. En una aplicación real, configurar
`axiz.messaging.sqs.queues`, `axiz.messaging.destinations`, los límites de consumo y una DLQ
o política de redrive. Cada handler debe ser idempotente. No guardar credenciales en el YAML.

Con Docker activo y el framework instalado, desde `axiz-demo`:

```bash
./mvnw -pl examples/sqs-consumer -Pquality -Daxiz.it.requireDocker=true clean verify
```

El reactor completo ejecuta este ejemplo antes de validar la matriz de capacidades.
