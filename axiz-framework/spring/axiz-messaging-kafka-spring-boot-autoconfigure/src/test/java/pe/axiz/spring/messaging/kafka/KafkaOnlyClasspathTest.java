package pe.axiz.spring.messaging.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mock.env.MockEnvironment;
import pe.axiz.spring.kafka.KafkaConnectionProperties.ClientKind;
import pe.axiz.spring.messaging.AxizMessagingAutoConfiguration;

class KafkaOnlyClasspathTest {
  @Test
  void kafkaDoesNotLoadAwsOrRedisWithoutTheirIntegrationArtifacts() {
    for (String name :
        new String[] {
          "software.amazon.awssdk.auth.credentials.AwsCredentials",
          "software.amazon.msk.auth.iam.IAMLoginModule",
          "io.lettuce.core.RedisClient",
          "pe.axiz.spring.aws.AxizAwsIdentityAutoConfiguration"
        }) {
      assertThrows(ClassNotFoundException.class, () -> Class.forName(name));
    }
    new ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                AxizMessagingAutoConfiguration.class, AxizMessagingKafkaAutoConfiguration.class))
        .run(ctx -> assertThat(ctx).hasNotFailed());
  }

  @Test
  void selectingAnAbsentAuthenticationIntegrationFailsBeforeCreatingClients() {
    var env =
        new MockEnvironment()
            .withProperty("axiz.messaging.kafka.bootstrap-servers", "localhost:9092")
            .withProperty("axiz.messaging.kafka.authentication", "absent-provider");
    var settings =
        Binder.get(env).bindOrCreate("axiz.messaging.kafka", AxizMessagingKafkaProperties.class);
    var beans = new DefaultListableBeanFactory();
    assertThrows(
        IllegalStateException.class,
        () ->
            AxizMessagingKafkaAutoConfiguration.common(
                settings,
                env,
                beans.getBeanProvider(KafkaClientAuthentication.class),
                null,
                ClientKind.PRODUCER));
  }
}
