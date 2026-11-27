package pe.axiz.kit.r2dbc;

import java.net.URI;
import java.util.Locale;
import java.util.Map;
import java.util.StringJoiner;

/** Utilidades de URL de R2DBC (Flyway migra sobre JDBC; los tokens IAM necesitan host y puerto). */
public final class R2dbcUrls {

  /**
   * Host, puerto y base de datos de una URL de R2DBC.
   *
   * @param host nombre del host
   * @param port puerto (5432 cuando falta)
   * @param database nombre de la base de datos, o vacío
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
   * URL JDBC de la misma base de datos PostgreSQL: {@code r2dbc:postgresql://h:5432/db} y {@code
   * r2dbc:pool:postgresql://...} se convierten en {@code jdbc:postgresql://h:5432/db}. De los
   * parámetros de la query se conservan solo los que tienen equivalente en JDBC ({@code
   * sslMode}/{@code sslmode} como {@code sslmode}, {@code applicationName} como {@code
   * ApplicationName}); se descartan las opciones del driver y del pool.
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
