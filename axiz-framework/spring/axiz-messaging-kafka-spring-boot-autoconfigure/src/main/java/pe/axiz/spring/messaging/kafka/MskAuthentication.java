package pe.axiz.spring.messaging.kafka;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Rechaza un listener MSK IAM conocido con IAM desactivado explícitamente, sin restringir Kafka
 * genérico.
 */
final class MskAuthentication {
  private static final Pattern IAM_LISTENER =
      Pattern.compile(
          "[^:]+\\.(?:kafka|kafka-serverless)\\.[a-z0-9-]+\\.amazonaws\\.com(?:\\.cn)?:(?:9098|9198|20098)");

  private MskAuthentication() {}

  static void validate(String servers, boolean iam) {
    if (iam) return;
    for (String server : servers.split(",", -1)) {
      if (IAM_LISTENER.matcher(server.trim().toLowerCase(Locale.ROOT)).matches()) {
        throw new IllegalArgumentException(
            "An MSK IAM listener requires an MSK IAM authentication integration; use the listener"
                + " matching the intended authentication method");
      }
    }
  }
}
