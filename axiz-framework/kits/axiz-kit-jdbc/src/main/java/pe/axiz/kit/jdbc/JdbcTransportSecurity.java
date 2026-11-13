package pe.axiz.kit.jdbc;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import pe.axiz.core.commons.Environments;
import pe.axiz.core.commons.net.DataTransportSecurityPolicy;

/** Validates effective PostgreSQL options before constructing or starting a JDBC pool. */
public final class JdbcTransportSecurity {
  private JdbcTransportSecurity() {}

  /** URL options override Properties, matching pgJDBC and the AWS PostgreSQL wrapper. */
  public static void validate(
      String url, Properties driverProperties, DataTransportSecurityPolicy policy) {
    if (url.startsWith("jdbc:h2:mem:") || url.startsWith("jdbc:hsqldb:mem:")) {
      Environments.requireSingleProcess(
          policy.environment(),
          "embedded database",
          "shared database",
          "embedded state is local to one process");
      return;
    }
    if (!url.startsWith("jdbc:postgresql:") && !url.startsWith("jdbc:aws-wrapper:postgresql:"))
      throw new IllegalStateException(
          "Register an explicit transport validator for this JDBC driver");
    Map<String, String> effective = new HashMap<>();
    driverProperties
        .stringPropertyNames()
        .forEach(key -> effective.put(key, driverProperties.getProperty(key)));
    int query = url.indexOf('?');
    if (query >= 0) {
      Map<String, String> queryOptions = new HashMap<>();
      for (String pair : url.substring(query + 1).split("&", -1)) {
        int separator = pair.indexOf('=');
        String key =
            URLDecoder.decode(
                separator < 0 ? pair : pair.substring(0, separator), StandardCharsets.UTF_8);
        String value =
            URLDecoder.decode(
                separator < 0 ? "" : pair.substring(separator + 1), StandardCharsets.UTF_8);
        if (queryOptions.putIfAbsent(key, value) != null)
          throw new IllegalStateException("Duplicate JDBC connection option");
      }
      effective.putAll(queryOptions);
    }
    String mode = effective.get("sslmode");
    boolean verified =
        "verify-full".equals(mode)
            || (mode == null
                && ("true".equals(effective.get("ssl")) || "".equals(effective.get("ssl"))));
    String factory = effective.get("sslfactory");
    if (factory != null
        && !factory.equals("org.postgresql.ssl.LibPQFactory")
        && !factory.equals("org.postgresql.ssl.DefaultJavaSSLFactory")) verified = false;
    String hostnameVerifier = effective.get("sslhostnameverifier");
    if (hostnameVerifier != null
        && !hostnameVerifier.equals("org.postgresql.ssl.PGjdbcHostnameVerifier")) verified = false;
    policy.requireVerified(verified, verified, "PostgreSQL JDBC");
  }
}
