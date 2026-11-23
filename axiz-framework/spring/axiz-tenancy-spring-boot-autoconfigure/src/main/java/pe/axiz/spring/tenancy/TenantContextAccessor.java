package pe.axiz.spring.tenancy;

import io.micrometer.context.ThreadLocalAccessor;
import org.jspecify.annotations.Nullable;
import pe.axiz.core.tenancy.TenantContext;

/** Propaga {@link TenantContext} entre hilos y operadores de Reactor (context-propagation). */
public class TenantContextAccessor implements ThreadLocalAccessor<String> {

  public static final String KEY = "axiz.tenant";

  @Override
  public Object key() {
    return KEY;
  }

  @Override
  public @Nullable String getValue() {
    return TenantContext.current().orElse(null);
  }

  @Override
  public void setValue(String value) {
    TenantContext.restore(value);
  }

  @Override
  public void setValue() {
    TenantContext.restore(null);
  }
}
