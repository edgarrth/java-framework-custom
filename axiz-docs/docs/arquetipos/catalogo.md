<!-- GENERADO por scripts/sync_reference.py desde axiz-framework: no editar -->
# Catálogo de arquetipos

La primera estructura, stack y persistencia de cada lista es el valor por defecto. Las extensiones son opcionales.

| Arquetipo | Rol | Estructuras | Extensiones | Stack | Persistencia | Despliegue | API REST |
|---|---|---|---|---|---|---|---|
| `business` | Dueño de las reglas de negocio del dominio | hexagonal, layered | cqrs | servlet, reactive | aurora, dynamodb | deployment | sí |
| `core` | Fachada hacia los sistemas internos de registro | hexagonal |  | servlet, reactive | none | deployment | sí |
| `gateway` | Fachada y seguridad para sistemas externos | hexagonal |  | servlet, reactive | none | deployment | sí |
| `ux` | Experiencia de canal sin persistencia de dominio | bff |  | servlet, reactive | none | deployment | sí |
| `support` | Servicio de soporte reutilizable entre dominios | hexagonal, layered |  | servlet, reactive | none, aurora, dynamodb | deployment | sí |
| `event-processor` | Consume eventos de dominio sin API REST pública | eda, hexagonal | cqrs | servlet, reactive | none, aurora, dynamodb | deployment | no |
| `batch` | Procesamiento programado de archivos y conciliación | batch |  | servlet | aurora | cronjob | no |
| `library` | Librería Java plana para el ecosistema (aplican las reglas de la capa 1) | none |  | servlet | no aplica | none | no |
| `starter` | Nueva capacidad de axiz (librería de capa 1 más starter Spring Boot de capa 3) | none |  | servlet | no aplica | none | no |

## business

Dueño de las reglas de negocio del dominio

Capacidades incluidas: [`foundation`](../capacidades/foundation.md), [`errors`](../capacidades/errors.md), [`logging`](../capacidades/logging.md), [`observability`](../capacidades/observability.md), [`metrics`](../capacidades/metrics.md), [`config`](../capacidades/config.md), [`sensitive`](../capacidades/sensitive.md), [`web`](../capacidades/web.md), [`i18n`](../capacidades/i18n.md), [`security`](../capacidades/security.md), [`audit`](../capacidades/audit.md), [`secrets`](../capacidades/secrets.md)

## core

Fachada hacia los sistemas internos de registro

Capacidades incluidas: [`foundation`](../capacidades/foundation.md), [`errors`](../capacidades/errors.md), [`logging`](../capacidades/logging.md), [`observability`](../capacidades/observability.md), [`metrics`](../capacidades/metrics.md), [`config`](../capacidades/config.md), [`sensitive`](../capacidades/sensitive.md), [`web`](../capacidades/web.md), [`i18n`](../capacidades/i18n.md), [`security`](../capacidades/security.md), [`http-client`](../capacidades/http-client.md), [`resilience`](../capacidades/resilience.md), [`secrets`](../capacidades/secrets.md), [`audit`](../capacidades/audit.md)

## gateway

Fachada y seguridad para sistemas externos

Capacidades incluidas: [`foundation`](../capacidades/foundation.md), [`errors`](../capacidades/errors.md), [`logging`](../capacidades/logging.md), [`observability`](../capacidades/observability.md), [`metrics`](../capacidades/metrics.md), [`config`](../capacidades/config.md), [`sensitive`](../capacidades/sensitive.md), [`web`](../capacidades/web.md), [`security`](../capacidades/security.md), [`http-client`](../capacidades/http-client.md), [`resilience`](../capacidades/resilience.md), [`secrets`](../capacidades/secrets.md), [`audit`](../capacidades/audit.md), [`idempotency`](../capacidades/idempotency.md)

## ux

Experiencia de canal sin persistencia de dominio

Capacidades incluidas: [`foundation`](../capacidades/foundation.md), [`errors`](../capacidades/errors.md), [`logging`](../capacidades/logging.md), [`observability`](../capacidades/observability.md), [`metrics`](../capacidades/metrics.md), [`config`](../capacidades/config.md), [`sensitive`](../capacidades/sensitive.md), [`web`](../capacidades/web.md), [`i18n`](../capacidades/i18n.md), [`security`](../capacidades/security.md), [`http-client`](../capacidades/http-client.md), [`resilience`](../capacidades/resilience.md), [`cache`](../capacidades/cache.md)

```yaml
axiz.web.api-version.enabled: true
axiz.web.api-version.supported:
- '1'
axiz.web.api-version.default-version: '1'
axiz.bff.timeout: 3s
```

## support

Servicio de soporte reutilizable entre dominios

Capacidades incluidas: [`foundation`](../capacidades/foundation.md), [`errors`](../capacidades/errors.md), [`logging`](../capacidades/logging.md), [`observability`](../capacidades/observability.md), [`metrics`](../capacidades/metrics.md), [`config`](../capacidades/config.md), [`sensitive`](../capacidades/sensitive.md), [`web`](../capacidades/web.md), [`i18n`](../capacidades/i18n.md), [`security`](../capacidades/security.md)

## event-processor

Consume eventos de dominio sin API REST pública

Capacidades incluidas: [`foundation`](../capacidades/foundation.md), [`errors`](../capacidades/errors.md), [`logging`](../capacidades/logging.md), [`observability`](../capacidades/observability.md), [`metrics`](../capacidades/metrics.md), [`config`](../capacidades/config.md), [`sensitive`](../capacidades/sensitive.md), [`security`](../capacidades/security.md), [`messaging`](../capacidades/messaging.md), [`messaging-kafka`](../capacidades/messaging-kafka.md), [`inbox`](../capacidades/inbox.md)

```yaml
axiz.messaging.consumer.max-attempts: 5
axiz.messaging.kafka.topics:
- example-events
```

## batch

Procesamiento programado de archivos y conciliación

Capacidades incluidas: [`foundation`](../capacidades/foundation.md), [`errors`](../capacidades/errors.md), [`logging`](../capacidades/logging.md), [`observability`](../capacidades/observability.md), [`metrics`](../capacidades/metrics.md), [`config`](../capacidades/config.md), [`sensitive`](../capacidades/sensitive.md), [`storage`](../capacidades/storage.md), [`storage-s3`](../capacidades/storage-s3.md), [`audit`](../capacidades/audit.md), [`secrets`](../capacidades/secrets.md)

```yaml
spring.main.web-application-type: none
spring.batch.job.enabled: true
spring.batch.jdbc.initialize-schema: never
job.input-key: ${JOB_INPUT_KEY:in/input.csv}
```

## library

Librería Java plana para el ecosistema (aplican las reglas de la capa 1)

## starter

Nueva capacidad de axiz (librería de capa 1 más starter Spring Boot de capa 3)
