<!-- generated -->
# axiz-tenancy-spring-boot-autoconfigure

Resolución del tenant desde el token o una cabecera confiable y propagación a HTTP, mensajería e Hibernate (autoconfiguración).

## Dependencia

```xml
<dependency>
  <groupId>pe.axiz</groupId>
  <artifactId>axiz-tenancy-spring-boot-autoconfigure</artifactId>
</dependency>
```

La versión la gestiona `axiz-bom` (los servicios heredan `axiz-parent-service`).

## Tipos principales

| Tipo | Propósito |
|---|---|
| `AxizTenancyAutoConfiguration` | Multi-tenancy, solo cuando `axiz.tenancy.enabled=true`: tenant tomado del token o de una cabecera confiable, vinculado a la petición, propagado a logs, llamadas salientes y mensajes, y aplicado a las entidades `@TenantId` de Hibernate. |
| `AxizTenancyHibernateAutoConfiguration` | Soporte de `@TenantId` de Hibernate: el tenant actual filtra y marca las entidades con tenant. |
| `AxizTenancyProperties` | Multi-tenancy (`axiz.tenancy.*`); solo está activo cuando el servicio lo declara. |
| `TenantContextAccessor` | Propaga `TenantContext` entre hilos y operadores de Reactor (context-propagation). |
| `TenantFilter` | Vincula el tenant a la petición (se ejecuta después de la autenticación). |
| `TenantFilterSupport` | Lógica compartida de los filtros de tenant servlet y reactivo. |
| `TenantIdentifierResolver` | Resolvedor de tenant de Hibernate para entidades con `@TenantId`: las filas se filtran y se marcan con el tenant actual. |
| `TenantWebFilter` | Vinculación reactiva del tenant mediante el contexto de Reactor (`TenantContextAccessor#KEY`). |

## Pruebas

Las pruebas unitarias están en `src/test/java` y documentan el comportamiento esperado; se ejecutan con `./mvnw -pl spring/axiz-tenancy-spring-boot-autoconfigure verify`.
