package pe.axiz.spring.config.aws;

import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.support.DefaultSingletonBeanRegistry;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Posee solo los recursos creados por este bootstrap; las extensiones registradas explícitamente
 * siguen siendo prestadas.
 */
final class AwsBootstrapOwner implements AutoCloseable {
  record Lease(Object provider, Runnable release) {}

  private final Supplier<Lease> factory;
  private @Nullable Lease lease;
  private @Nullable Runnable clientClose;
  private boolean closed;
  private boolean transferred;

  AwsBootstrapOwner(Supplier<Lease> factory) {
    this.factory = factory;
  }

  synchronized Object provider() {
    if (closed) throw new IllegalStateException("Bootstrap identity already closed");
    if (lease == null) lease = factory.get();
    return lease.provider();
  }

  synchronized void ownsClient(Runnable close) {
    clientClose = close;
  }

  synchronized void transfer(ConfigurableApplicationContext context) {
    if (transferred) return;
    if (!(context.getBeanFactory() instanceof DefaultSingletonBeanRegistry registry))
      throw new IllegalStateException(
          "Bootstrap resource lifecycle requires a singleton resource registry");
    registry.registerDisposableBean("axizParameterStoreBootstrapResources", this::close);
    transferred = true;
  }

  synchronized void failed() {
    if (!transferred) close();
  }

  @Override
  public synchronized void close() {
    if (closed) return;
    closed = true;
    Throwable failure = null;
    try {
      if (clientClose != null) clientClose.run();
    } catch (RuntimeException | Error problem) {
      failure = problem;
    }
    if (lease != null) {
      try {
        lease.release().run();
      } catch (RuntimeException | Error cleanup) {
        if (failure == null) failure = cleanup;
        else if (failure != cleanup) failure.addSuppressed(cleanup);
      }
    }
    if (failure instanceof RuntimeException problem) throw problem;
    if (failure instanceof Error problem) throw problem;
  }
}
