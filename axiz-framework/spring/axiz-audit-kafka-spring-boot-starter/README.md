# axiz-audit-kafka-spring-boot-starter

Publicación de auditoría en Kafka.

Este módulo es dueño de la API `pe.axiz.spring.audit.kafka.starter`.

## Selección

Las versiones las gestiona `axiz-bom`; las aplicaciones heredan `axiz-parent-service`.

```xml
<dependency>
  <groupId>pe.axiz</groupId>
  <artifactId>axiz-audit-kafka-spring-boot-starter</artifactId>
</dependency>
```

Este artefacto compone dependencias; su configuración y su contrato operativo se documentan en
[`axiz-audit-kafka-spring-boot-autoconfigure`](../axiz-audit-kafka-spring-boot-autoconfigure/README.md).

## Uso desde el catálogo

```bash
axiz add audit-kafka
```

Capacidades requeridas: `audit`, `messaging-kafka`.

El catálogo aporta las siguientes propiedades. Los marcadores de entorno deben resolverse en la
configuración del servicio; no son credenciales ni infraestructura aprovisionada.

```yaml
axiz.audit.kafka.topic: ${AXIZ_AUDIT_TOPIC:audit}
```

## Operación

Los tópicos y el acceso al broker se aprovisionan con TLS/SASL, timeouts de transporte acotados y
una durabilidad adecuada al contrato del evento. La entrega puede repetirse; los consumidores usan
identificadores de evento estables y deduplicación con inbox. La confirmación del productor no
equivale a confirmar una transacción de negocio.
