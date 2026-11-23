package pe.axiz.spring.foundation;

import java.security.SecureRandom;

/**
 * Inicializa la semilla del DRBG estándar del JDK en el hilo de bootstrap, antes de los handshakes
 * asíncronos.
 */
public final class ReactiveRandomBootstrap {
  private static final SecureRandom RANDOM = new SecureRandom();

  private ReactiveRandomBootstrap() {}

  public static void initialize() {
    if (!"DRBG".equals(RANDOM.getAlgorithm())) {
      throw new IllegalStateException(
          "Reactive services require the JDK DRBG as the default SecureRandom; "
              + "on Linux start the JVM with -Djava.security.egd=file:/dev/./urandom");
    }
    RANDOM.nextBytes(new byte[64]);
  }
}
