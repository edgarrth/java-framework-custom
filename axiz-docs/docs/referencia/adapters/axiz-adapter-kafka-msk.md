<!-- GENERADO por scripts/sync_reference.py desde axiz-framework: no editar -->
# axiz-adapter-kafka-msk

Autenticación IAM de Amazon MSK con credenciales de AWS propias.

Este módulo es dueño de la API `pe.axiz.adapter.kafka.msk`.

## Selección

Las versiones las gestiona `axiz-bom`; las aplicaciones heredan `axiz-parent-service`.

```xml
<dependency>
  <groupId>pe.axiz</groupId>
  <artifactId>axiz-adapter-kafka-msk</artifactId>
</dependency>
```

## Tipos

Tipos públicos que declara este módulo:

- `AxizIamClientCallbackHandler`
- `MskIam`

## Operación

Los tópicos y el acceso al broker se aprovisionan con TLS/SASL, timeouts de transporte acotados y
una durabilidad adecuada al contrato del evento. La entrega puede repetirse; los consumidores usan
identificadores de evento estables y deduplicación con inbox. La confirmación del productor no
equivale a confirmar una transacción de negocio.

## Verificación

Desde `axiz-framework`:

```bash
./mvnw -pl adapters/axiz-adapter-kafka-msk -am test
```

La verificación completa es `./mvnw clean install -Pquality -Daxiz.it.requireDocker=true`.
Las pruebas de integración de proveedores están en `quality/axiz-store-integration-tests` y requieren Docker.
