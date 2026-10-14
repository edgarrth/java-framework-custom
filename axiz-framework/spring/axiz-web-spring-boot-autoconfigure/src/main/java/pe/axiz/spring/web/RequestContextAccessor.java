package pe.axiz.spring.web;

import io.micrometer.context.ThreadLocalAccessor;
import org.jspecify.annotations.Nullable;
import pe.axiz.core.commons.RequestContext;
import pe.axiz.core.commons.RequestMetadata;

/**
 * Bridges {@link RequestContext} with Micrometer context-propagation, so the metadata follows
 * Reactor operators, executors and virtual threads. Registered through {@code
 * META-INF/services/io.micrometer.context.ThreadLocalAccessor}.
 */
public class RequestContextAccessor implements ThreadLocalAccessor<RequestMetadata> {

  @Override
  public Object key() {
    return RequestContext.KEY;
  }

  @Override
  public @Nullable RequestMetadata getValue() {
    return RequestContext.get();
  }

  @Override
  public void setValue(RequestMetadata value) {
    RequestContext.restore(value);
  }

  @Override
  public void setValue() {
    RequestContext.restore(null);
  }
}
