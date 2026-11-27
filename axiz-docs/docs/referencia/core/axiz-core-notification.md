<!-- GENERADO por scripts/sync_reference.py desde axiz-framework: no editar -->
# axiz-core-notification

Solicitudes de notificación (correo, SMS, push) con plantillas, publicadas como eventos.

## Dependencia

```xml
<dependency>
  <groupId>pe.axiz</groupId>
  <artifactId>axiz-core-notification</artifactId>
</dependency>
```

La versión la gestiona `axiz-bom` (los servicios heredan `axiz-parent-service`).

## Tipos principales

| Tipo | Propósito |
|---|---|
| `AsyncNotificationSender` | Confirmación nativa del broker para una solicitud de notificación, cancelable junto con su llamador. |
| `AsyncEventNotificationSender` | Publicación nativa del evento con el mismo identificador, correlación y esquema que el emisor imperativo. |
| `EventNotificationSender` | Publica las solicitudes como eventos `notification.requested.v1` hacia el servicio que entrega (normalmente por SNS para difusión o por SQS). |
| `NotificationChannel` | Canales de entrega. |
| `NotificationRequest` | Solicitud para notificar a un destinatario con una plantilla. |
| `NotificationSender` | Puerto para solicitar notificaciones. |
| `TemplateRenderer` | Renderiza los marcadores `{{name`}}. |

## Contrato de entrega

Ambos emisores comparten una misma política de construcción del evento. El identificador de la
solicitud es el identificador del evento y de la deduplicación; los identificadores de correlación
y los parámetros de plantilla conservan su esquema. Completar confirma la aceptación del broker; el
servicio de entrega se encarga de llegar al destinatario, de los reintentos con el proveedor y de la
deduplicación. El emisor asíncrono nunca invoca al publicador imperativo ni delega en un worker.

## Pruebas

Las pruebas unitarias están en `src/test/java` y documentan el comportamiento esperado; se ejecutan con `./mvnw -pl core/axiz-core-notification verify`.
