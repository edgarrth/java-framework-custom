<!-- generated -->
# axiz-notification-spring-boot-autoconfigure

Solicitudes de notificación publicadas como eventos hacia el servicio que las entrega (autoconfiguración).

## Dependencia

```xml
<dependency>
  <groupId>pe.axiz</groupId>
  <artifactId>axiz-notification-spring-boot-autoconfigure</artifactId>
</dependency>
```

La versión la gestiona `axiz-bom` (los servicios heredan `axiz-parent-service`).

## Tipos principales

| Tipo | Propósito |
|---|---|
| `AxizNotificationAutoConfiguration` | Notificaciones (email, SMS, push) como eventos `notification.requested.v1` hacia el servicio que las entrega; el destino es `axiz.notification.destination` (por defecto `notifications`). |

## Notificaciones reactivas

Las aplicaciones reactivas exponen `ReactiveNotifications` y `AsyncNotificationSender`, que usan el
puerto de mensajería nativo. Una aplicación con solo un publicador imperativo falla al arranque en lugar
de usarlo como respaldo. `axiz.notification.enabled=false` desactiva ambas pilas; un emisor nativo
provisto reemplaza al de por defecto. La publicación empieza al suscribirse, espera la aceptación del
broker y cancela el envío nativo cuando se cancela. Los ids de solicitud y el esquema
`notification.requested.v1` son compatibles con los servicios de entrega y los productores imperativos.

## Pruebas

Las pruebas unitarias están en `src/test/java` y documentan el comportamiento esperado; se ejecutan con `./mvnw -pl spring/axiz-notification-spring-boot-autoconfigure verify`.
