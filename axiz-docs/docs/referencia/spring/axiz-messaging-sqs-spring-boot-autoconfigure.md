<!-- GENERADO por scripts/sync_reference.py desde axiz-framework: no editar -->
# axiz-messaging-sqs-spring-boot-autoconfigure

Integración SQS para mensajería CloudEvents.

Este módulo es dueño de la API `pe.axiz.spring.messaging.sqs`.

## Selección

Las versiones las gestiona `axiz-bom`; las aplicaciones heredan `axiz-parent-service`.

```xml
<dependency>
  <groupId>pe.axiz</groupId>
  <artifactId>axiz-messaging-sqs-spring-boot-starter</artifactId>
</dependency>
```

El starter asociado selecciona este proveedor. El código de aplicación usa los puertos de la
capacidad; las clases de autoconfiguración no se importan en controladores, servicios de
aplicación ni modelos de dominio.

## Tipos

Tipos públicos que declara este módulo:

- `AxizMessagingSqsAutoConfiguration`
- `AxizMessagingSqsClientProperties`
- `AxizMessagingSqsProperties`
- `AxizNativeMessagingSqsAutoConfiguration`
- `SqsEventConsumer`

## Configuración

Prefijos de propiedades propios: `axiz.messaging.aws.*`, `axiz.messaging.sqs.*`.

## Uso desde el catálogo

```bash
axiz add messaging-sqs
```

Capacidades requeridas: `messaging`.

El catálogo aporta las siguientes propiedades. Los marcadores de entorno deben resolverse en la
configuración del servicio; no son credenciales ni infraestructura aprovisionada.

```yaml
axiz.messaging.sqs.wait-time: 20s
```

## Operación

Se proporcionan región, identidad de carga de trabajo, TLS y permisos mínimos para los recursos de
AWS seleccionados. Las tablas, colas, tópicos, claves y buckets de producción se aprovisionan con IaC
revisada. Las pruebas con LocalStack ejercitan el comportamiento, pero no certifican el IAM desplegado
ni la durabilidad del servicio. Nunca se colocan credenciales de larga duración en el YAML generado.

Para una dead-letter queue FIFO explícita, el consumidor conserva el grupo de mensajes de origen y usa el
ID del mensaje SQS de origen como ID de deduplicación estable entre reintentos de entrega. Cuando un
origen estándar no tiene grupo, el destino usa `axiz-dead-letter`. La confirmación del mensaje original
sigue a la confirmación del envío a la dead-letter queue. La receta ejecutable
`axiz-demo/examples/sqs-consumer` cubre el consumo estándar y FIFO, los reintentos transitorios, la
entrega explícita a la DLQ y el redrive nativo en LocalStack en ambas pilas web.

Las colas FIFO reciben un mensaje por poll, de modo que un reintento o aplazamiento no puede procesar un
mensaje posterior del mismo grupo desde un lote precargado. Esta estrategia conservadora preserva el orden
a costa del rendimiento por lotes; las colas estándar reciben hasta diez mensajes. La renovación de
visibilidad y la liquidación se coordinan por recibo, de modo que un heartbeat no puede sobrescribir el
backoff de reintento ni el redrive. El arranque exige un destino dead-letter explícito o una política de
redrive nativa de SQS. Al depender del redrive nativo se otorga `sqs:GetQueueAttributes`. Una política
faltante o una cola inaccesible hace fallar el arranque en lugar de crear un ciclo infinito de reentrega
de mensajes envenenados.

## Verificación

Desde `axiz-framework`:

```bash
./mvnw -pl spring/axiz-messaging-sqs-spring-boot-autoconfigure -am test
```

La verificación completa es `./mvnw clean install -Pquality -Daxiz.it.requireDocker=true`.
Las pruebas de integración de proveedores están en `quality/axiz-store-integration-tests` y requieren Docker.
