package pe.axiz.spring.foundation.jdbc;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.DatabasePopulatorUtils;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import pe.axiz.kit.sql.SqlIdentifiers;

/**
 * Creates framework tables from {@code axiz/sql/<name>-<dialect>.sql}. Mode {@code embedded} (the
 * default) runs only on H2, for tests and local runs; deployed services own the schema through
 * their Flyway migrations, which the initializr generates from the same scripts.
 */
public final class SchemaInitializer implements InitializingBean {

  /** When the schema is created. */
  public enum Mode {
    EMBEDDED,
    ALWAYS,
    NEVER
  }

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification =
          "DataSource is an injected collaborator whose identity and lifecycle must be shared, "
              + "not copied.")
  private final DataSource dataSource;

  private final String name;
  private final Mode mode;
  private final Map<String, String> tableNames;
  private static final Pattern SQL_TOKEN =
      Pattern.compile(
          "(?s)'(?:''|[^'])*'|\"(?:\"\"|[^\"])*\"|--[^\\r\\n]*|/\\*.*?\\*/|(?<identifier>[a-zA-Z_][a-zA-Z0-9_]*)");

  public SchemaInitializer(DataSource dataSource, String name, Mode mode) {
    this(dataSource, name, mode, Map.of());
  }

  /** Maps canonical table names, including their auxiliary tables and indexes, to service names. */
  public SchemaInitializer(
      DataSource dataSource, String name, Mode mode, Map<String, String> tableNames) {
    this.dataSource = Objects.requireNonNull(dataSource);
    this.name = Objects.requireNonNull(name);
    this.mode = Objects.requireNonNull(mode);
    this.tableNames = Map.copyOf(tableNames);
    this.tableNames.forEach(
        (canonical, configured) -> {
          requireIdentifier(canonical);
          requireIdentifier(configured);
        });
  }

  @Override
  public void afterPropertiesSet() {
    if (mode == Mode.NEVER) {
      return;
    }
    String product = SqlDialects.product(dataSource);
    boolean h2 = product.contains("h2");
    if (mode == Mode.EMBEDDED && !h2) {
      return;
    }
    String dialect =
        h2 ? "h2" : product.contains("postgres") ? "postgresql" : product.toLowerCase(Locale.ROOT);
    var resource = new ClassPathResource("axiz/sql/" + name + "-" + dialect + ".sql");
    String script;
    try {
      script = resource.getContentAsString(StandardCharsets.UTF_8);
    } catch (IOException failure) {
      throw new IllegalStateException("Cannot load schema initialization resource", failure);
    }
    String configured = configure(script);
    ResourceDatabasePopulator populator =
        new ResourceDatabasePopulator(
            new ByteArrayResource(configured.getBytes(StandardCharsets.UTF_8), resource.getPath()));
    populator.setContinueOnError(false);
    DatabasePopulatorUtils.execute(populator, dataSource);
  }

  String configure(String script) {
    return SQL_TOKEN
        .matcher(script)
        .replaceAll(
            match ->
                Matcher.quoteReplacement(
                    match.group("identifier") == null
                        ? match.group()
                        : configuredIdentifier(match.group())));
  }

  private String configuredIdentifier(String original) {
    // Match the longest canonical prefix once; never recursively rewrite replacement names.
    String canonical =
        tableNames.keySet().stream()
            .filter(key -> original.equals(key) || original.startsWith(key + "_"))
            .max(Comparator.comparingInt(String::length))
            .orElse(null);
    if (canonical == null) return original;
    String replacement = tableNames.get(canonical) + original.substring(canonical.length());
    requireIdentifier(replacement);
    return replacement;
  }

  private static void requireIdentifier(String name) {
    SqlIdentifiers.require(name);
  }
}
