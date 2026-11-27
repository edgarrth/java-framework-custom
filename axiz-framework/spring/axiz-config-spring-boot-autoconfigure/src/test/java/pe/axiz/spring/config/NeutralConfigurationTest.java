package pe.axiz.spring.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.mock.env.MockEnvironment;

class NeutralConfigurationTest {
  @Test
  void configurationWorksWithNoProviderOnTheActualClasspath() {
    for (String type :
        List.of(
            "software.amazon.awssdk.auth.credentials.AwsCredentialsProvider",
            "io.awspring.cloud.autoconfigure.AwsAutoConfiguration",
            "org.apache.kafka.clients.producer.KafkaProducer",
            "org.springframework.data.redis.core.RedisTemplate")) {
      assertThatThrownBy(() -> Class.forName(type)).isInstanceOf(ClassNotFoundException.class);
    }
    var env = new MockEnvironment().withProperty("axiz.config.environment", "local");
    new AxizConfigEnvironmentPostProcessor().postProcessEnvironment(env, new SpringApplication());
    new AxizConfigValidationEnvironmentPostProcessor()
        .postProcessEnvironment(env, new SpringApplication());
    assertThat(env.getProperty("spring.cloud.aws.parameterstore.enabled")).isNull();
  }

  @Test
  void importedConfigurationCannotChangeTheEnvironmentUsedForBootstrap() {
    var env = new MockEnvironment().withProperty("axiz.config.environment", "prod");
    new AxizConfigEnvironmentPostProcessor().postProcessEnvironment(env, new SpringApplication());
    env.setProperty("axiz.config.environment", "test");
    assertThatThrownBy(
            () ->
                new AxizConfigValidationEnvironmentPostProcessor()
                    .postProcessEnvironment(env, new SpringApplication()))
        .hasMessageContaining("differs from the bootstrap selection");
  }
}
