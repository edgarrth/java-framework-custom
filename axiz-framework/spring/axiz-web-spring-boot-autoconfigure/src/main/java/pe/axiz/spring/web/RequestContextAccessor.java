package pe.axiz.spring.web;

import io.micrometer.context.ThreadLocalAccessor;
import org.jspecify.annotations.Nullable;
import pe.axiz.core.context.RequestContext;
import pe.axiz.core.context.RequestMetadata;

/**
 * Conecta {@link RequestContext} con context-propagation de Micrometer, de modo que los metadatos
 * siguen a los operadores de Reactor, los executors y los hilos virtuales. Se registra mediante
 * {@code META-INF/services/io.micrometer.context.ThreadLocalAccessor}.
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
