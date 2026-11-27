<!-- GENERADO por scripts/sync_reference.py desde axiz-framework: no editar -->
# axiz-adapter-messaging-kafka

Adaptador publicador y consumidor de Amazon MSK (Kafka) con autenticación IAM.

## Dependencia

```xml
<dependency>
  <groupId>pe.axiz</groupId>
  <artifactId>axiz-adapter-messaging-kafka</artifactId>
</dependency>
```

La versión la gestiona `axiz-bom` (los servicios heredan `axiz-parent-service`).

## Tipos principales

| Tipo | Propósito |
|---|---|
| `KafkaEventRecords` | Codificación CloudEvents binaria compartida para la publicación síncrona y asíncrona en Kafka. |
| `KafkaEnvelopes` | Convierte los registros de Kafka consumidos en sobres. |
| `KafkaEventPublisher` | Publica CloudEvents (modo binario) en Amazon MSK. |

Tipos relacionados: `AxizIamClientCallbackHandler`, `MskIam` están en `axiz-adapter-kafka-msk`.

## Autenticación con AWS

Las dependencias opcionales `aws-msk-iam-auth` y `axiz-adapter-aws` habilitan IAM. Los proyectos
`messaging-kafka` generados incluyen ambas. Un consumidor MSK independiente las incluye de forma
explícita; Kafka genérico sin IAM no las necesita. `MskIam.clientProperties()` selecciona el
callback de `pe.axiz.adapter.messaging.kafka.aws`. Con IRSA se rechazan los roles o perfiles
sobrescritos en JAAS y una identidad web inválida no puede caer en las credenciales del nodo. El
callback es dueño de su fuente y la cierra. La librería IAM sigue manejando su protocolo SASL y la firma.

Spring entrega `AxizIamClientCallbackHandler.CREDENTIALS_SOURCE_FACTORY_CONFIG`
(`axiz.msk.credentials.source-factory`) con un `Supplier<AwsCredentialSource>`. Cada callback obtiene
y cierra su propio recurso; el proveedor de aplicación seleccionado o la identidad por defecto del
contexto siguen compartidos y los cierra su dueño. Quien lo usa fuera de Spring puede entregar el
mismo contrato de fábrica. Sin él, el callback es dueño de una fuente de carga de trabajo cuando se
declara IRSA o conserva el delegado IAM estándar cuando no hay IRSA.

El callback usa las clases internas `AWSCredentialsCallback` y `region.ConfigurableRegionProvider`
de la librería IAM, en `software.amazon.msk.auth.iam.internals`. El BOM fija `aws-msk-iam-auth` en
2.3.8; al actualizarla, `MskIrsaTest` debe seguir pasando. Con IRSA declarado, las credenciales
estáticas del sistema o del entorno no pueden sobrescribir la identidad web.

## Pruebas

Las pruebas unitarias están en `src/test/java` y documentan el comportamiento esperado; se ejecutan con `./mvnw -pl adapters/axiz-adapter-messaging-kafka verify`.
