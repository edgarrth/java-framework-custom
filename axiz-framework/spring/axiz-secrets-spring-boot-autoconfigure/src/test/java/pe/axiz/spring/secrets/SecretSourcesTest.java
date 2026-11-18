package pe.axiz.spring.secrets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import pe.axiz.core.secrets.SecretResolver;
import pe.axiz.core.secrets.SecretValue;

/** Which secret sources are consulted, how values are cached, and how mounts become properties. */
class SecretSourcesTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(AxizSecretsAutoConfiguration.class));

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
}
