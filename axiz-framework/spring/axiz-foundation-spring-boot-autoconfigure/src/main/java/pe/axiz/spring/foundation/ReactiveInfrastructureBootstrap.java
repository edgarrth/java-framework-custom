package pe.axiz.spring.foundation;

import org.springframework.util.ClassUtils;

/** Initializes optional context SPI discovery and entropy before request subscriptions. */
public final class ReactiveInfrastructureBootstrap {
  private ReactiveInfrastructureBootstrap() {}

  public static void initialize() {
    ReactiveRandomBootstrap.initialize();
    if (ClassUtils.isPresent(
        "reactor.core.publisher.Mono", ReactiveInfrastructureBootstrap.class.getClassLoader()))
      ReactiveStages.initialize();
    if (ClassUtils.isPresent(
        "io.micrometer.context.ContextRegistry",
        ReactiveInfrastructureBootstrap.class.getClassLoader())) Micrometer.initialize();
  }

  private static final class Micrometer {
    static void initialize() {
      io.micrometer.context.ContextRegistry.getInstance();
    }
  }
}
