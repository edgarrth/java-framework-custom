package pe.axiz.spring.logging;

import java.util.function.Supplier;
import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.jspecify.annotations.Nullable;
import org.springframework.util.function.SingletonSupplier;

/**
 * Resuelve el interceptor real en el primer uso, para que los advisors de infraestructura no
 * fuercen la inicialización temprana de los beans de la aplicación.
 */
final class LazyInterceptor implements MethodInterceptor {

  private final SingletonSupplier<MethodInterceptor> delegate;

  LazyInterceptor(Supplier<MethodInterceptor> delegate) {
    this.delegate = SingletonSupplier.of(delegate);
  }

  @Override
  public @Nullable Object invoke(MethodInvocation invocation) throws Throwable {
    return delegate.obtain().invoke(invocation);
  }
}
