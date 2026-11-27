package pe.axiz.spring.jpa.aws;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;
import pe.axiz.adapter.aws.identity.AwsCredentialSource;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;

/**
 * Puente SPI con tipos seguros: solo identidades opacas de pool cruzan el límite de propiedades de
 * texto de JDBC.
 */
final class JdbcIamCredentialScopes {
  static final String PREFIX = "axiz-jpa-credentials-";
  private static final ConcurrentHashMap<String, AwsCredentialSource> SOURCES =
      new ConcurrentHashMap<>();

  private JdbcIamCredentialScopes() {}

  static String open(AwsCredentialSource source) {
    String id = PREFIX + UUID.randomUUID();
    if (SOURCES.putIfAbsent(id, source) != null) {
      source.close();
      throw new IllegalStateException("JDBC credential scope collision");
    }
    return id;
  }

  static @Nullable AwsCredentialsProvider find(String id) {
    AwsCredentialSource source = SOURCES.get(id);
    return source == null ? null : source.provider();
  }

  static void close(String id) {
    AwsCredentialSource source = SOURCES.remove(id);
    if (source != null) source.close();
  }
}
