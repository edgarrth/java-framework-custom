package pe.axiz.spring.r2dbc.aws;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import io.r2dbc.spi.ConnectionFactoryOptions;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.reactivestreams.Publisher;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import pe.axiz.adapter.databaseauth.aws.RdsIamPasswordProvider;
import pe.axiz.kit.r2dbc.DatabasePasswordProvider;
import pe.axiz.spring.aws.AxizAwsIdentityAutoConfiguration;
import pe.axiz.spring.r2dbc.AxizR2dbcAutoConfiguration;
import pe.axiz.spring.r2dbc.AxizR2dbcFlywayPasswordAutoConfiguration;
import pe.axiz.spring.r2dbc.AxizR2dbcPasswordAutoConfiguration;
import pe.axiz.spring.r2dbc.FlywayPasswordCustomizer;
import pe.axiz.spring.r2dbc.ReaderDatabase;
import reactor.core.publisher.Mono;
import software.amazon.awssdk.regions.Region;

/**
 * La conexión a la réplica de lectura, los tokens IAM, Flyway sobre IAM y la auditoría de filas.
 */
class R2dbcIamBindingTest {

  private static final Duration T = Duration.ofSeconds(5);
  private static final String READER = "r2dbc:axiztest://reader.cluster-ro.local:5433/payments";

  @BeforeEach
  void localCredentials() {
    RecordingDriver.CREATED.clear();
    // valores de relleno para la cadena por defecto del SDK: el token IAM se firma localmente y no
    // se hace ninguna llamada
    System.setProperty("aws.accessKeyId", "local-only-access-key");
    System.setProperty("aws.secretAccessKey", "local-only-secret-key");
  }

  @AfterEach
  void clearCredentials() {
    System.clearProperty("aws.accessKeyId");
    System.clearProperty("aws.secretAccessKey");
    System.clearProperty("aws.region");
  }

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withPropertyValues(
              "axiz.config.environment=test", "axiz.transport.allow-insecure-data-stores=true")
          .withConfiguration(AutoConfigurations.of(AxizAwsIdentityAutoConfiguration.class))
          .withPropertyValues("axiz.r2dbc.encryption-context=account.number")
          .withConfiguration(
              AutoConfigurations.of(
                  AxizR2dbcAutoConfiguration.class,
                  AxizR2dbcIamAutoConfiguration.class,
                  AxizR2dbcPasswordAutoConfiguration.class))
          .withPropertyValues(
              "axiz.r2dbc.encryption-context=account.number",
              "spring.r2dbc.url=r2dbc:postgresql://writer.cluster.local/payments");

  private static ConnectionFactoryOptions behindThePool() {
    assertEquals(1, RecordingDriver.CREATED.size(), "the pool created one driver factory");
    return RecordingDriver.CREATED.get(0);
  }

  @Test
  void withIamTheReaderSignsATokenForEachNewConnection() {
    runner
        .withPropertyValues(
            "axiz.r2dbc.reader.url=" + READER,
            "spring.r2dbc.username=app",
            "spring.r2dbc.password=never-used",
            "axiz.r2dbc.iam.enabled=true",
            "axiz.r2dbc.iam.region=us-east-1")
        .run(
            ctx -> {
              ctx.getBean(ReaderDatabase.class);
              Object password = behindThePool().getValue(ConnectionFactoryOptions.PASSWORD);
              assertInstanceOf(Publisher.class, password, "resolved when a connection opens");
              String token = String.valueOf(Mono.from((Publisher<?>) password).block(T));
              assertThat(token)
                  .startsWith("reader.cluster-ro.local:5433/")
                  .contains("DBUser=app")
                  .contains("X-Amz-Signature=")
                  .doesNotContain("never-used");
            });
  }

  @Test
  void theRegionComesFromTheSettingOrTheSdkChain() {
    assertEquals(Region.SA_EAST_1, AwsIamTokens.region(" sa-east-1 "));
    assertEquals(Region.US_EAST_2, AwsIamTokens.region("  ", " us-east-2 "));
    assertEquals(Region.US_EAST_2, AwsIamTokens.region(null, "us-east-2"));
    assertEquals(Region.SA_EAST_1, AwsIamTokens.region(" sa-east-1 ", "us-east-2"));
    System.setProperty("aws.region", "us-west-2");
    assertEquals(Region.US_WEST_2, AwsIamTokens.region(null));
    assertEquals(Region.US_WEST_2, AwsIamTokens.region(" "));
  }

  @Test
  void theWriterUsesIamTokensAndFlywayGetsOneForTheMigration() {
    new ApplicationContextRunner()
        .withPropertyValues(
            "axiz.config.environment=test", "axiz.transport.allow-insecure-data-stores=true")
        .withConfiguration(AutoConfigurations.of(AxizAwsIdentityAutoConfiguration.class))
        .withPropertyValues("axiz.r2dbc.encryption-context=account.number")
        .withConfiguration(
            AutoConfigurations.of(
                AxizR2dbcIamAutoConfiguration.class,
                AxizR2dbcPasswordAutoConfiguration.class,
                AxizR2dbcFlywayPasswordAutoConfiguration.class))
        .withPropertyValues(
            "axiz.r2dbc.iam.enabled=true",
            "axiz.aws.region=us-east-1",
            "spring.r2dbc.url=r2dbc:postgresql://writer.cluster.local/payments",
            "spring.r2dbc.username=app")
        .run(
            ctx -> {
              DatabasePasswordProvider passwords = ctx.getBean(DatabasePasswordProvider.class);
              assertInstanceOf(RdsIamPasswordProvider.class, passwords);
              assertThat(passwords.password().toString())
                  .startsWith("writer.cluster.local:5432/")
                  .contains("DBUser=app");
              assertThat(ctx).hasSingleBean(FlywayPasswordCustomizer.class);
            });
  }
}
