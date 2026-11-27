# axiz-messaging-kafka-spring-boot-starter

Mensajería CloudEvents sobre Kafka.

Este módulo es dueño de la API `pe.axiz.spring.messaging.kafka.starter`.

## Selección

Las versiones las gestiona `axiz-bom`; las aplicaciones heredan `axiz-parent-service`.

```xml
<dependency>
  <groupId>pe.axiz</groupId>
  <artifactId>axiz-messaging-kafka-spring-boot-starter</artifactId>
</dependency>
```

Este artefacto compone dependencias; su configuración y su contrato operativo se documentan en
[`axiz-messaging-kafka-spring-boot-autoconfigure`](../axiz-messaging-kafka-spring-boot-autoconfigure/README.md).

## Uso desde el catálogo

```bash
axiz add messaging-kafka
```

Capacidades requeridas: `messaging`.

El catálogo aporta las siguientes propiedades. Los marcadores de entorno deben resolverse en la
configuración del servicio; no son credenciales ni infraestructura aprovisionada.

```yaml
spring.kafka.bootstrap-servers: ${KAFKA_BOOTSTRAP_SERVERS:localhost:9092}
```

## Operación

Los tópicos y el acceso al broker se aprovisionan con TLS/SASL, timeouts de transporte acotados y
una durabilidad adecuada al contrato del evento. La entrega puede repetirse; los consumidores usan
identificadores de evento estables y deduplicación con inbox. La confirmación del productor no
equivale a confirmar una transacción de negocio.
