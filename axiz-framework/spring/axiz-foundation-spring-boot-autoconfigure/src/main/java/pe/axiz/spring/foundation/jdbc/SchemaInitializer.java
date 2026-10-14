package pe.axiz.spring.foundation.jdbc;

import java.util.Locale;
import java.util.Objects;
import javax.sql.DataSource;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.DatabasePopulatorUtils;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

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

  private final DataSource dataSource;
  private final String name;
  private final Mode mode;

  public SchemaInitializer(DataSource dataSource, String name, Mode mode) {
    this.dataSource = Objects.requireNonNull(dataSource);
    this.name = Objects.requireNonNull(name);
    this.mode = Objects.requireNonNull(mode);
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
    ResourceDatabasePopulator populator =
        new ResourceDatabasePopulator(
            new ClassPathResource("axiz/sql/" + name + "-" + dialect + ".sql"));
    populator.setContinueOnError(false);
    DatabasePopulatorUtils.execute(populator, dataSource);
  }
}
