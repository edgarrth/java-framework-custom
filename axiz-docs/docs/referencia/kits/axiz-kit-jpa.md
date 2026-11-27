<!-- GENERADO por scripts/sync_reference.py desde axiz-framework: no editar -->
# axiz-kit-jpa

Entidad base JPA con UUIDv7, auditoría, versionado y convertidores de atributos cifrados.

## Dependencia

```xml
<dependency>
  <groupId>pe.axiz</groupId>
  <artifactId>axiz-kit-jpa</artifactId>
</dependency>
```

La versión la gestiona `axiz-bom` (los servicios heredan `axiz-parent-service`).

## Tipos principales

| Tipo | Propósito |
|---|---|
| `AuditListener` | Completa las columnas de auditoría con el proveedor de auditor registrado y el reloj. |
| `AuditableEntity` | Entidad con columnas de auditoría de creación y modificación (instantes UTC y nombres de principal). |
| `AuditorProvider` | Quién modifica los datos: el nombre del principal autenticado, o explícitamente `unattributed` cuando no hay identidad disponible. |
| `BaseEntity` | Entidad JPA base: id UUIDv7 ordenado por tiempo y asignado por la aplicación (adecuado para índices y conocido antes de insertar) y bloqueo optimista. |
| `EncryptedConverter` | Cifra un atributo String con AES-256-GCM: `@Convert(converter = EncryptedConverter.class)`. |

## Pruebas

Las pruebas unitarias están en `src/test/java` y documentan el comportamiento esperado; se ejecutan con `./mvnw -pl kits/axiz-kit-jpa verify`.

Los procesos en segundo plano proporcionan una identidad de carga de trabajo explícita mediante su
proveedor de auditor; una identidad ausente o vacía se marca `unattributed` y nunca se atribuye en silencio a
un sistema confiable.
