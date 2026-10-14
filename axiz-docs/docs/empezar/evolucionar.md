# Agregar funcionalidad

Un servicio nace con su arquetipo y crece con la CLI. Todos los comandos leen `.axiz/project.yaml`
para saber el estilo, el stack y los paquetes del servicio, y escriben código que ya respeta las
reglas de arquitectura.

## Capacidades

```bash
axiz catalog capabilities
axiz add outbox idempotency-redis
```

`axiz add` agrega el starter (y las capacidades que requiere), sus propiedades en
`application.yaml` con valores por defecto seguros, las llaves que el ConfigMap de cada ambiente
debe definir (comentadas en `values-<env>.yaml`), los secretos del ExternalSecret, las tablas o
migraciones que necesite (numeradas después de la última `V<n>__`), el perfil local y un ejemplo
de uso. Ver [Capacidades](../capacidades/index.md).

## Artefactos

```bash
axiz generate usecase approve-payment
axiz generate error-code limit-exceeded --category unprocessable --title "Daily limit exceeded"
axiz generate http-client core-banking --contract core-banking.yaml
axiz generate event-handler payment-approved
axiz generate migration add-payment-channel
axiz generate mapper payment --source CreatePaymentRequest --target pe.axiz.payments.domain.Payment
```

Tipos: `usecase`, `command`, `query`, `handler`, `event`, `event-handler`, `entity`,
`repository`, `controller`, `dto`, `mapper`, `http-client`, `migration`, `error-code` y `test`.
Cada tipo se ubica en el paquete que manda el estilo (por ejemplo, un `http-client` va a
`adapter/out/<cliente>` en hexagonal y a `client/<cliente>` en layered) y no sobrescribe archivos
existentes salvo con `--force`.

Antes de escribir archivos, `generate` revisa que el servicio tenga lo que el código generado
necesita para compilar:

- `handler` y `event-handler` piden una capacidad de mensajería (`axiz add messaging-kafka` o
  `messaging-sqs`).
- `entity` y `repository` piden persistencia. En servicios servlet generan JPA; en servicios
  reactivos sobre Aurora generan Spring Data R2DBC (`AuditableRow` y `ReactiveCrudRepository`).
- `mapper` agrega MapStruct al `pom.xml`. El procesador ya viene configurado en el parent.

`http-client` genera una interfaz `@HttpExchange` desde el contrato del proveedor, solo con sus
modelos, y la registra como cliente con nombre en `axiz.http-client.clients.<nombre>` (timeouts,
reintentos, autenticación). Ver [Clientes HTTP y SOAP](../guias/clientes.md).

## El contrato cambia

```bash
axiz contract sync               # nuevas operaciones → esqueletos; borradas → reporte
axiz contract diff old.yaml new.yaml
```

`sync` compara el contrato con la última versión sincronizada (`.axiz/openapi.snapshot.yaml`):
genera controladores y casos de uso para las operaciones nuevas, propone los cambios de las
existentes sin tocar tu código y falla ante cambios incompatibles salvo con `--allow-breaking`.
Ver [Contrato primero](../herramientas/contratos.md).

## Diagnóstico y actualización

```bash
axiz doctor                      # herramientas, versiones, contrato, configuración, anti-patrones
axiz upgrade --to 1.3.0 --dry-run
axiz upgrade --to 1.3.0
```

`upgrade` cambia la versión del parent y del manifiesto y aplica las recetas de OpenRewrite de esa
versión (ver [Migración](../migracion/index.md)).
