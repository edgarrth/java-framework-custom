package pe.axiz.spring.secrets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.Ordered;
import org.springframework.mock.env.MockEnvironment;
import pe.axiz.core.secrets.SecretResolver;
import pe.axiz.core.secrets.SecretValue;
import pe.axiz.spring.foundation.aws.AxizAwsIdentityAutoConfiguration;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.ssm.SsmClient;

/** Which secret sources are consulted, how values are cached, and how mounts become properties. */
class SecretSourcesTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(AxizAwsIdentityAutoConfiguration.class))
          .withConfiguration(
              AutoConfigurations.of(
                  AxizSecretsAwsAutoConfiguration.class, AxizSecretsAutoConfiguration.class))
          .withPropertyValues("axiz.aws.region=us-east-1", "axiz.secrets.environment=false");

  @Test
  void awsSourcesAreOffUnlessEnabled() {
    runner.run(
        ctx ->
            assertThat(ctx)
                .hasSingleBean(SecretResolver.class)
                .doesNotHaveBean(SecretSource.class)
                .doesNotHaveBean(SecretsManagerClient.class)
                .doesNotHaveBean(SsmClient.class));
  }

  @Test
  void enabledAwsSourcesAreChainedAfterTheMounts() {
    runner
        .withPropertyValues(
            "axiz.secrets.secrets-manager.enabled=true",
            "axiz.secrets.secrets-manager.prefix=axiz/prod/payments/",
            "axiz.secrets.parameter-store.enabled=true",
            "axiz.secrets.parameter-store.path=/axiz/prod/payments/")
        .run(
            ctx -> {
              assertThat(ctx).hasNotFailed();
              List<String> names =
                  ctx.getBeanProvider(SecretSource.class)
                      .orderedStream()
                      .map(SecretSource::name)
                      .sorted()
                      .toList();
              assertEquals(List.of("parameter-store", "secrets-manager"), names);
            });
  }

  @Test
  void parameterStoreNeedsAPath() {
    runner
        .withPropertyValues("axiz.secrets.parameter-store.enabled=true")
        .run(
            ctx ->
                assertThat(ctx)
                    .hasFailed()
                    .getFailure()
                    .rootCause()
                    .hasMessageContaining("axiz.secrets.parameter-store.path is required"));
  }

  @Test
  void resolvedValuesAreCachedForTheConfiguredTime() {
    AtomicInteger lookups = new AtomicInteger();
    runner
        .withPropertyValues("axiz.secrets.cache-ttl=10m")
        .withBean(
            Clock.class, () -> Clock.fixed(Instant.parse("2026-10-18T10:00:00Z"), ZoneOffset.UTC))
        .withBean(
            SecretSource.class,
            () ->
                new SecretSource(
                    "counting",
                    name -> {
                      lookups.incrementAndGet();
                      return Optional.of(SecretValue.of("v" + lookups.get()));
                    }))
        .run(
            ctx -> {
              SecretResolver secrets = ctx.getBean(SecretResolver.class);
              assertEquals("v1", secrets.resolve("db-password").orElseThrow().reveal());
              assertEquals("v1", secrets.resolve("db-password").orElseThrow().reveal());
              assertEquals(1, lookups.get());
            });
  }

  @Test
  void secretResolutionCanBeReplacedOrSwitchedOff() {
    runner
        .withPropertyValues("axiz.secrets.enabled=false")
        .run(ctx -> assertThat(ctx).doesNotHaveBean(SecretResolver.class));
  }

  @Test
  void theConfigTreeImportIsMergedOnceAndCanBeDisabled(@TempDir Path dir) {
    SecretsConfigTreeEnvironmentPostProcessor epp = new SecretsConfigTreeEnvironmentPostProcessor();
    assertTrue(epp.getOrder() < Ordered.HIGHEST_PRECEDENCE + 10);
    String entry = "optional:configtree:" + dir + "/";

    MockEnvironment existing =
        new MockEnvironment()
            .withProperty("axiz.secrets.directory", dir.toString())
            .withProperty("spring.config.import", "optional:file:/etc/axiz/extra.yaml");
    epp.postProcessEnvironment(existing, new SpringApplication());
    assertEquals(
        entry + ",optional:file:/etc/axiz/extra.yaml",
        existing.getProperty("spring.config.import"));
    epp.postProcessEnvironment(existing, new SpringApplication());
    assertEquals(
        entry + ",optional:file:/etc/axiz/extra.yaml",
        existing.getProperty("spring.config.import"));

    MockEnvironment off =
        new MockEnvironment()
            .withProperty("axiz.secrets.directory", dir + "/")
            .withProperty("axiz.secrets.config-tree", "false");
    epp.postProcessEnvironment(off, new SpringApplication());
    assertNull(off.getProperty("spring.config.import"));
  }
}
