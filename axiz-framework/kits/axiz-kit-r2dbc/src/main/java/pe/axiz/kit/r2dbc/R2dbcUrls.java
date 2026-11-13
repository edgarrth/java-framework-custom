package pe.axiz.kit.r2dbc;

import java.net.URI;
import java.util.Locale;
import java.util.Map;
import java.util.StringJoiner;

/** R2DBC URL helpers (Flyway still migrates over JDBC; IAM tokens need host and port). */
public final class R2dbcUrls {

  /**
   * Host, port and database of an R2DBC URL.
   *
   * @param host host name
   * @param port port (5432 when absent)
   * @param database database name, or empty
   */
  public record Endpoint(String host, int port, String database) {}

  private static final Map<String, String> JDBC_PARAMETERS =
      Map.of(
          "sslmode",
          "sslmode",
          "applicationname",
          "ApplicationName",
          "sslrootcert",
          "sslrootcert",
          "sslcert",
          "sslcert",
          "sslkey",
          "sslkey",
          "sslpassword",
          "sslpassword");

  private R2dbcUrls() {}

  /**
   * The JDBC URL of the same PostgreSQL database: {@code r2dbc:postgresql://h:5432/db} and {@code
   * r2dbc:pool:postgresql://...} become {@code jdbc:postgresql://h:5432/db}. Of the query
   * parameters only the ones with a JDBC equivalent are kept ({@code sslMode}/{@code sslmode} as
   * {@code sslmode}, {@code applicationName} as {@code ApplicationName}); driver and pool options
   * are dropped.
   */
  public static String toJdbc(String r2dbcUrl) {
    String rest = stripScheme(r2dbcUrl);
    if (!rest.toLowerCase(Locale.ROOT).startsWith("postgresql://")) {
      throw new IllegalArgumentException("Only PostgreSQL R2DBC URLs are supported");
    }
    int q = rest.indexOf('?');
    if (q < 0) {
      return "jdbc:" + rest;
    }
    StringJoiner query = new StringJoiner("&", "?", "").setEmptyValue("");
    for (String pair : rest.substring(q + 1).split("&", -1)) {
      int eq = pair.indexOf('=');
      if (eq <= 0) {
        continue;
      }
      String jdbcName = JDBC_PARAMETERS.get(pair.substring(0, eq).toLowerCase(Locale.ROOT));
      if (jdbcName != null) {
        query.add(jdbcName + pair.substring(eq));
      }
    }
    return "jdbc:" + rest.substring(0, q) + query;
  }

  public static Endpoint endpoint(String r2dbcUrl) {
    URI uri = URI.create(stripScheme(r2dbcUrl));
    if (uri.getHost() == null) {
      throw new IllegalArgumentException("R2DBC URL without host");
    }
    String path = uri.getPath() == null ? "" : uri.getPath();
    return new Endpoint(
        uri.getHost(), uri.getPort() < 0 ? 5432 : uri.getPort(), path.replaceFirst("^/", ""));
  }

  private static String stripScheme(String url) {
    if (!url.startsWith("r2dbc:")) {
      throw new IllegalArgumentException("Not an R2DBC URL");
    }
    String rest = url.substring("r2dbc:".length());
    if (rest.startsWith("pool:")) {
      rest = rest.substring("pool:".length());
    }
    return rest;
  }
}
