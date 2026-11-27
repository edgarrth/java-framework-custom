# axiz-messaging-kafka-spring-boot-autoconfigure

Integración Kafka para mensajería CloudEvents.

Este módulo es dueño de la API `pe.axiz.spring.messaging.kafka`.

## Selección

Las versiones las gestiona `axiz-bom`; las aplicaciones heredan `axiz-parent-service`.

```xml
<dependency>
  <groupId>pe.axiz</groupId>
  <artifactId>axiz-messaging-kafka-spring-boot-starter</artifactId>
</dependency>
```

El starter asociado selecciona este proveedor. El código de aplicación usa los puertos de la
capacidad; las clases de autoconfiguración no se importan en controladores, servicios de
aplicación ni modelos de dominio.

## Tipos

Tipos públicos que declara este módulo:

- `AxizMessagingKafkaAutoConfiguration`
- `AxizMessagingKafkaProperties`
- `KafkaClientAuthentication`
- `KafkaConsumers`

## Configuración

Prefijos de propiedades propios: `axiz.messaging.kafka.*`.

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

## Verificación

Desde `axiz-framework`:

```bash
./mvnw -pl spring/axiz-messaging-kafka-spring-boot-autoconfigure -am test
```

La verificación completa es `./mvnw clean install -Pquality -Daxiz.it.requireDocker=true`.
Las pruebas de integración de proveedores están en `quality/axiz-store-integration-tests` y requieren Docker.
