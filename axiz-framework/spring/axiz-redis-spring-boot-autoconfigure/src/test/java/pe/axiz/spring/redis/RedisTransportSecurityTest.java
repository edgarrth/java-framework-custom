package pe.axiz.spring.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.mock.env.MockEnvironment;

class RedisTransportSecurityTest {
  @Test
  void secureDefaultsNeverOverwriteExplicitOptionsAndAreIdempotent() {
    var env = new MockEnvironment();
    var defaults = new AxizRedisTransportEnvironmentPostProcessor();
    var application = new SpringApplication(Object.class);
    defaults.postProcessEnvironment(env, application);
    assertThat(env.getProperty("spring.data.redis.ssl.enabled", Boolean.class)).isTrue();
    env.withProperty("spring.data.redis.ssl.enabled", "false");
    defaults.postProcessEnvironment(env, application);
    defaults.postProcessEnvironment(env, application);
    assertThat(env.getProperty("spring.data.redis.ssl.enabled", Boolean.class)).isFalse();
    assertThat(
            env.getPropertySources().stream()
                .filter(source -> source.getName().equals("axizRedisTransportDefaults"))
                .count())
        .isEqualTo(1);
  }

  @Test
  void startedCustomFactoriesMustHaveBeenValidatedBeforeTheyCouldConnect() {
    var env = new MockEnvironment().withProperty("axiz.config.environment", "prod");
    var guard = AxizRedisTransportAutoConfiguration.axizRedisTransportGuard(env);
    var running = mock(LettuceConnectionFactory.class);
    when(running.isRunning()).thenReturn(true);
    assertThatThrownBy(() -> guard.postProcessBeforeInitialization(running, "custom"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("before starting");
    var unrelated = new Object();
    assertThat(guard.postProcessBeforeInitialization(unrelated, "unrelated")).isSameAs(unrelated);
  }

  @Test
  void validatesFinalLettuceConfigurationBeforeStartingTheClient() {
    var env = new MockEnvironment().withProperty("axiz.config.environment", "prod");
    var guard = AxizRedisTransportAutoConfiguration.axizRedisTransportGuard(env);
    var secure =
        new LettuceConnectionFactory(
            new RedisStandaloneConfiguration("never-connect.test", 6379),
            LettuceClientConfiguration.builder().useSsl().build());
    assertThatCode(() -> guard.postProcessBeforeInitialization(secure, "secure"))
        .doesNotThrowAnyException();
    var insecure =
        new LettuceConnectionFactory(
            new RedisStandaloneConfiguration("never-connect.test", 6379),
            LettuceClientConfiguration.builder().useSsl().disablePeerVerification().build());
    assertThatThrownBy(() -> guard.postProcessBeforeInitialization(insecure, "insecure"))
        .isInstanceOf(IllegalStateException.class);
    var plain = new LettuceConnectionFactory();
    assertThatThrownBy(() -> guard.postProcessBeforeInitialization(plain, "plain"))
        .isInstanceOf(IllegalStateException.class);
    env.withProperty("axiz.config.environment", "local")
        .withProperty("axiz.transport.allow-insecure-data-stores", "true");
    assertThatCode(() -> guard.postProcessBeforeInitialization(plain, "local"))
        .doesNotThrowAnyException();
    env.withProperty("axiz.config.environment", "prod");
    assertThatThrownBy(() -> guard.postProcessBeforeInitialization(plain, "production"))
        .isInstanceOf(IllegalStateException.class);
  }
}
