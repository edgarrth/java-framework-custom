package pe.axiz.spring.messaging.msk;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import pe.axiz.adapter.kafka.msk.AxizIamClientCallbackHandler;
import pe.axiz.spring.aws.AxizAwsIdentityAutoConfiguration;
import pe.axiz.spring.messaging.kafka.KafkaClientAuthentication;

class MskIntegrationTest {
  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(
              AutoConfigurations.of(
                  AxizAwsIdentityAutoConfiguration.class, AxizMessagingMskAutoConfiguration.class));

  @Test
  void mskIsExplicitAndSharesTheCredentialOwnerInsteadOfEmbeddingCredentials() {
    runner.run(ctx -> assertThat(ctx).doesNotHaveBean(KafkaClientAuthentication.class));
    runner
        .withPropertyValues("axiz.messaging.kafka.authentication=msk")
        .run(
            ctx -> {
              assertThat(ctx).hasNotFailed().hasSingleBean(KafkaClientAuthentication.class);
              var authentication = ctx.getBean(KafkaClientAuthentication.class);
              var properties = new HashMap<String, Object>();
              authentication.configure(properties);
              assertThat(authentication.name()).isEqualTo("msk");
              assertThat(properties)
                  .containsEntry("security.protocol", "SASL_SSL")
                  .containsEntry("sasl.mechanism", "AWS_MSK_IAM");
              assertThat(
                      properties.get(
                          AxizIamClientCallbackHandler.CREDENTIALS_SOURCE_FACTORY_CONFIG))
                  .isInstanceOf(Supplier.class);
            });
  }

  @Test
  void conflictingOrDisabledSelectionCreatesNoAuthenticationResource() {
    runner
        .withPropertyValues(
            "axiz.messaging.kafka.authentication=msk", "axiz.messaging.kafka.iam=false")
        .run(
            ctx ->
                assertThat(ctx)
                    .hasFailed()
                    .getFailure()
                    .hasRootCauseMessage(
                        "MSK authentication requires axiz.messaging.kafka.iam=true"));
    runner
        .withPropertyValues(
            "axiz.messaging.kafka.authentication=msk", "axiz.messaging.enabled=false")
        .run(ctx -> assertThat(ctx).doesNotHaveBean(KafkaClientAuthentication.class));
  }
}
