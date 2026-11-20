package pe.axiz.spring.foundation;

import io.micrometer.context.ContextRegistry;
import org.springframework.util.ClassUtils;

/** Initializes optional context SPI discovery and entropy before request subscriptions. */
public final class ReactiveInfrastructureBootstrap {
  private ReactiveInfrastructureBootstrap() {}

  public static void initialize() {
    ReactiveRandomBootstrap.initialize();
    // Netty probes optional native codecs while decoding HTTP responses. Their first load reads
    // native libraries from JAR files; perform that discovery on the bootstrap thread.
    initializeOptional("io.netty.handler.codec.compression.Zstd");
    initializeOptional("io.netty.handler.codec.compression.Brotli");
    if (ClassUtils.isPresent(
        "reactor.core.publisher.Mono", ReactiveInfrastructureBootstrap.class.getClassLoader()))
      ReactiveStages.initialize();
    if (ClassUtils.isPresent(
        "io.micrometer.context.ContextRegistry",
        ReactiveInfrastructureBootstrap.class.getClassLoader())) Micrometer.initialize();
  }

  // Only absence is optional. An installed but incompatible codec must fail startup, so an
  // application never accepts traffic with a corrupt classpath and delayed decoder failures.
  private static void initializeOptional(String type) {
    try {
      Class.forName(type, true, ReactiveInfrastructureBootstrap.class.getClassLoader());
    } catch (ClassNotFoundException absent) {
      // Optional transport dependency; applications without Netty need not bring it in.
    }
  }

  private static final class Micrometer {
    static void initialize() {
      ContextRegistry.getInstance();
    }
  }
}
