package pe.axiz.spring.r2dbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.ArrayList;
import java.util.List;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mock.env.MockEnvironment;
import pe.axiz.kit.r2dbc.DatabasePasswordProvider;
import pe.axiz.spring.foundation.aws.AxizAwsIdentityAutoConfiguration;

/** The startup migration authenticates with a fresh IAM token, never with a stored password. */
class FlywayIamCustomizerTest {

  /** Records the JDBC credentials Flyway is given, without opening a connection. */
  static final class RecordingConfiguration extends FluentConfiguration {
    @Nullable List<String> credentials;

    @Override
    public FluentConfiguration dataSource(String url, String user, String password) {
      credentials = new ArrayList<>(List.of(url, user, password));
      return this;
    }
  }

  @Test
  void theMigrationUsesTheFlywayUserAndAFreshToken() {
    int[] issued = {0};
    DatabasePasswordProvider tokens = () -> "token-" + ++issued[0];
    MockEnvironment env =
        new MockEnvironment()
            .withProperty("spring.flyway.url", "jdbc:postgresql://db/payments")
            .withProperty("spring.flyway.user", "migrator")
            .withProperty("spring.r2dbc.username", "app");
    RecordingConfiguration configuration = new RecordingConfiguration();
    new FlywayIamCustomizer(tokens, env).customize(configuration);
    assertEquals(
        List.of("jdbc:postgresql://db/payments", "migrator", "token-1"), configuration.credentials);
  }

  @Test
  void withoutAFlywayUserTheR2dbcUserIsReused() {
    MockEnvironment env =
        new MockEnvironment()
            .withProperty("spring.flyway.url", "jdbc:postgresql://db/payments")
            .withProperty("spring.r2dbc.username", "app");
    RecordingConfiguration configuration = new RecordingConfiguration();
    new FlywayIamCustomizer(() -> "token", env).customize(configuration);
    assertEquals(
        List.of("jdbc:postgresql://db/payments", "app", "token"), configuration.credentials);
  }

  @Test
  void withoutAJdbcUrlFlywayKeepsItsOwnDataSource() {
    RecordingConfiguration configuration = new RecordingConfiguration();
    new FlywayIamCustomizer(() -> "token", new MockEnvironment()).customize(configuration);
    assertNull(configuration.credentials);
  }

  @Test
  void theCustomizerExistsOnlyWithIamAndATokenProvider() {
    ApplicationContextRunner runner =
        new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AxizAwsIdentityAutoConfiguration.class))
            .withPropertyValues("axiz.r2dbc.encryption-context=account.number")
            .withConfiguration(AutoConfigurations.of(AxizR2dbcFlywayIamAutoConfiguration.class));
    runner
        .withBean(DatabasePasswordProvider.class, () -> () -> "token")
        .withPropertyValues("axiz.r2dbc.iam.enabled=true")
        .run(ctx -> assertThat(ctx).hasSingleBean(FlywayIamCustomizer.class));
    runner
        .withBean(DatabasePasswordProvider.class, () -> () -> "token")
        .run(ctx -> assertThat(ctx).doesNotHaveBean(FlywayIamCustomizer.class));
    runner
        .withPropertyValues("axiz.r2dbc.iam.enabled=true")
        .run(ctx -> assertThat(ctx).doesNotHaveBean(FlywayIamCustomizer.class));
  }
}
