package pe.axiz.spring.r2dbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import pe.axiz.kit.r2dbc.DatabasePasswordProvider;

class NeutralR2dbcCredentialsTest {
  @Test
  void actualClasspathContainsNoAwsOrRedisProvider() {
    for (String name :
        List.of(
            "software.amazon.awssdk.auth.credentials.AwsCredentialsProvider",
            "software.amazon.awssdk.services.rds.RdsUtilities",
            "org.springframework.data.redis.core.RedisTemplate")) {
      assertThatThrownBy(() -> Class.forName(name)).isInstanceOf(ClassNotFoundException.class);
    }
    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(AxizR2dbcPasswordAutoConfiguration.class))
        .withBean(DatabasePasswordProvider.class, () -> () -> "test-password")
        .run(context -> assertThat(context).hasNotFailed().hasBean("axizR2dbcPassword"));
  }

  @Test
  void readerConstructionFailureClosesItsOwnedCredentials() {
    var closed = new AtomicInteger();
    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(AxizR2dbcAutoConfiguration.class))
        .withBean(
            DatabasePasswordProviderFactory.class,
            () ->
                (url, username) -> {
                  assertThat(url).isEqualTo("r2dbc:axiztest://reader.example/payments");
                  assertThat(username).isEqualTo("service");
                  return new OwnedPassword(closed);
                })
        .withPropertyValues(
            "axiz.r2dbc.reader.url=r2dbc:axiztest://reader.example/payments",
            "spring.r2dbc.username=service",
            "axiz.r2dbc.reader.max-size=0",
            "axiz.config.environment=test",
            "axiz.transport.allow-insecure-data-stores=true")
        .run(context -> assertThat(context).hasFailed());
    assertThat(closed).hasValue(1);
  }

  private record OwnedPassword(AtomicInteger closed)
      implements DatabasePasswordProvider, AutoCloseable {
    @Override
    public CharSequence password() {
      return "test-password";
    }

    @Override
    public void close() {
      closed.incrementAndGet();
    }
  }
}
