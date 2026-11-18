package pe.axiz.spring.r2dbc;

import java.util.Objects;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.springframework.boot.flyway.autoconfigure.FlywayConfigurationCustomizer;
import org.springframework.core.env.Environment;
import pe.axiz.kit.r2dbc.DatabasePasswordProvider;

/**
 * Gives Flyway a JDBC connection authenticated with an IAM token (the migration runs once at
 * startup, well within the token's lifetime).
 */
public final class FlywayPasswordCustomizer implements FlywayConfigurationCustomizer {

  private final DatabasePasswordProvider passwords;
  private final Environment env;

  public FlywayPasswordCustomizer(DatabasePasswordProvider passwords, Environment env) {
    this.passwords = Objects.requireNonNull(passwords);
    this.env = Objects.requireNonNull(env);
  }

  @Override
  public void customize(FluentConfiguration configuration) {
    String url = env.getProperty("spring.flyway.url");
    if (url == null) {
      return;
    }
    configuration.dataSource(
        url,
        env.getProperty("spring.flyway.user", env.getProperty("spring.r2dbc.username", "")),
        passwords.password().toString());
  }
}
