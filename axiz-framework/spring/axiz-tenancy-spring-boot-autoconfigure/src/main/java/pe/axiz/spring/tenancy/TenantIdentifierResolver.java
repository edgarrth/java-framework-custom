package pe.axiz.spring.tenancy;

import org.hibernate.context.spi.CurrentTenantIdentifierResolver;
import pe.axiz.core.tenancy.TenantContext;

/**
 * Hibernate tenant resolver for entities with {@code @TenantId}: rows are filtered and stamped with
 * the current tenant. Outside a tenant (startup, schedulers) the {@code system} id is used, which
 * matches no tenant data.
 */
public class TenantIdentifierResolver implements CurrentTenantIdentifierResolver<String> {

  public static final String SYSTEM = "system";

  @Override
  public String resolveCurrentTenantIdentifier() {
    return TenantContext.current().orElse(SYSTEM);
  }

  @Override
  public boolean validateExistingCurrentSessions() {
    return true;
  }
}
