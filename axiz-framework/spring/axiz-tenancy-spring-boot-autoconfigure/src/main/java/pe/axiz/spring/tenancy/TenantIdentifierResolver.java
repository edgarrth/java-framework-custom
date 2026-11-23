package pe.axiz.spring.tenancy;

import org.hibernate.context.spi.CurrentTenantIdentifierResolver;
import pe.axiz.core.tenancy.TenantContext;

/**
 * Resolvedor de tenant de Hibernate para entidades con {@code @TenantId}: las filas se filtran y se
 * marcan con el tenant actual. Fuera de un tenant (arranque, schedulers) se usa el id {@code
 * system}, que no coincide con datos de ningún tenant.
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
