<!-- GENERADO por scripts/sync_reference.py desde axiz-framework: no editar -->
# axiz-core-domain

Bloques de construcción DDD: raíz de agregado, entidad, objeto de valor, eventos de dominio, identificadores tipados y objetos de valor comunes.

## Dependencia

```xml
<dependency>
  <groupId>pe.axiz</groupId>
  <artifactId>axiz-core-domain</artifactId>
</dependency>
```

La versión la gestiona `axiz-bom` (los servicios heredan `axiz-parent-service`).

## Tipos principales

| Tipo | Propósito |
|---|---|
| `AggregateRoot` | Raíz de agregado: límite de consistencia que registra eventos de dominio mientras cambia. |
| `DomainErrors` | Errores que lanzan los bloques de construcción del dominio. |
| `DomainEvent` | Algo que ocurrió en el dominio. |
| `EmailAddress` | Dirección de correo electrónico validada y normalizada (en minúsculas). |
| `Entity` | Entidad de dominio con identidad. |
| `EventTypes` | Utilidades para nombrar tipos de evento. |
| `PersonalDocument` | Documento de identidad de una persona o empresa (DNI, RUC o carné de extranjería). |
| `PhoneNumber` | Número de teléfono en formato E.164. |
| `Rules` | Cláusulas de guarda para agregados que lanzan errores de negocio del catálogo. |
| `Specification` | Regla de negocio sobre un candidato, componible con `and`, `or` y `not`. |
| `TypedId` | Identificador tipado respaldado por un UUID, implementado con records. |
| `ValueObject` | Marca de objetos de valor inmutables comparados por valor (normalmente records). |

## Pruebas

Las pruebas unitarias están en `src/test/java` y documentan el comportamiento esperado; se ejecutan con `./mvnw -pl core/axiz-core-domain verify`.
