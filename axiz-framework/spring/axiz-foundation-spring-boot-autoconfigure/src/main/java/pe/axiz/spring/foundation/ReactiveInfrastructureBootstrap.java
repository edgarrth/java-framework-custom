package pe.axiz.spring.foundation;

import io.micrometer.context.ContextRegistry;
import org.springframework.util.ClassUtils;

/**
 * Inicializa el descubrimiento opcional de SPI del contexto y la entropía antes de las
 * suscripciones de solicitudes.
 */
public final class ReactiveInfrastructureBootstrap {
  private ReactiveInfrastructureBootstrap() {}

  public static void initialize() {
    ReactiveRandomBootstrap.initialize();
    // Netty sondea los códecs nativos opcionales al decodificar respuestas HTTP. Su primera carga
    // lee bibliotecas nativas desde archivos JAR; ese descubrimiento se realiza en el hilo de
    // bootstrap.
    initializeOptional("io.netty.handler.codec.compression.Zstd");
    initializeOptional("io.netty.handler.codec.compression.Brotli");
    if (ClassUtils.isPresent(
        "reactor.core.publisher.Mono", ReactiveInfrastructureBootstrap.class.getClassLoader()))
      ReactiveStages.initialize();
    if (ClassUtils.isPresent(
        "io.micrometer.context.ContextRegistry",
        ReactiveInfrastructureBootstrap.class.getClassLoader())) Micrometer.initialize();
  }

  // Solo la ausencia es opcional. Un códec instalado pero incompatible debe hacer fallar el
  // arranque, de modo que una aplicación nunca acepte tráfico con un classpath corrupto y fallos
  // diferidos del decodificador.
  private static void initializeOptional(String type) {
    try {
      Class.forName(type, true, ReactiveInfrastructureBootstrap.class.getClassLoader());
    } catch (ClassNotFoundException absent) {
      // Dependencia de transporte opcional; las aplicaciones sin Netty no necesitan incluirla.
    }
  }

  private static final class Micrometer {
    static void initialize() {
      ContextRegistry.getInstance();
    }
  }
}
