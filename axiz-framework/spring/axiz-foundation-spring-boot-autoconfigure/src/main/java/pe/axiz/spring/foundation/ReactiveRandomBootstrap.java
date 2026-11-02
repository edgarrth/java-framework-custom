package pe.axiz.spring.foundation;

import java.security.SecureRandom;

/** Seeds the standard JDK DRBG on the bootstrap thread, before asynchronous handshakes. */
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
