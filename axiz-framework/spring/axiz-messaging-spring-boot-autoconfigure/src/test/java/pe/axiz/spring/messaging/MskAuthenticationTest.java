package pe.axiz.spring.messaging;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.mock.env.MockEnvironment;
import pe.axiz.spring.foundation.aws.AwsIdentity;

class MskAuthenticationTest {
  private org.springframework.context.annotation.AnnotationConfigApplicationContext identityContext;

  @org.junit.jupiter.api.BeforeEach
  void createIdentityContext() {
    identityContext =
        new org.springframework.context.annotation.AnnotationConfigApplicationContext(
            pe.axiz.spring.foundation.aws.AxizAwsIdentityAutoConfiguration.class);
  }

  @org.junit.jupiter.api.AfterEach
  void closeIdentityContext() {
    identityContext.close();
  }

  @Test
  void validatesTheEffectiveConfigurationBeforeConstructingKafkaClients() {
    var env =
        new MockEnvironment()
            .withProperty(
                "spring.kafka.bootstrap-servers", "b-1.orders.kafka.us-east-1.amazonaws.com:9098")
            .withProperty("axiz.messaging.kafka.iam", "false");
    var properties = Binder.get(env).bindOrCreate("axiz.messaging", AxizMessagingProperties.class);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            AxizMessagingKafkaAutoConfiguration.common(
                properties,
                env,
                identityContext.getBean(AwsIdentity.class),
                null,
                pe.axiz.spring.kafka.KafkaConnectionProperties.ClientKind.PRODUCER));
    env.withProperty("axiz.messaging.kafka.iam", "true");
    var secured = Binder.get(env).bindOrCreate("axiz.messaging", AxizMessagingProperties.class);
    assertEquals(
        "AWS_MSK_IAM",
        AxizMessagingKafkaAutoConfiguration.common(
                secured,
                env,
                identityContext.getBean(AwsIdentity.class),
                null,
                pe.axiz.spring.kafka.KafkaConnectionProperties.ClientKind.PRODUCER)
            .get("sasl.mechanism"));
    env.withProperty("axiz.messaging.kafka.iam", "false")
        .withProperty("axiz.messaging.kafka.bootstrap-servers", "broker.internal:9098");
    var generic = Binder.get(env).bindOrCreate("axiz.messaging", AxizMessagingProperties.class);
    assertEquals(
        "broker.internal:9098",
        AxizMessagingKafkaAutoConfiguration.common(
                generic,
                env,
                identityContext.getBean(AwsIdentity.class),
                null,
                pe.axiz.spring.kafka.KafkaConnectionProperties.ClientKind.PRODUCER)
            .get("bootstrap.servers"));
  }

  @Test
  void rejectsKnownIamListenersOnlyWhenIamIsDisabled() {
    for (String host :
        List.of(
            "b-1.orders.id.c2.kafka.us-east-1.amazonaws.com",
            "boot-id.c2.kafka-serverless.us-gov-west-1.amazonaws.com",
            "b-2.id.kafka.cn-north-1.amazonaws.com.cn")) {
      for (int port : List.of(9098, 9198, 20098)) {
        String servers = "localhost:9092, " + host + ":" + port;
        assertThrows(
            IllegalArgumentException.class, () -> MskAuthentication.validate(servers, false));
        assertDoesNotThrow(() -> MskAuthentication.validate(servers, true));
      }
      assertDoesNotThrow(() -> MskAuthentication.validate(host + ":9094", false));
    }
  }

  @Test
  void leavesGenericKafkaAndOtherListenersAlone() {
    for (String servers :
        List.of(
            "localhost:9098",
            "broker.internal:9198",
            "[::1]:20098",
            "kafka.us-east-1.amazonaws.com.evil.test:9098",
            "b-1.kafka.us-east-1.amazonaws.com:19098")) {
      assertDoesNotThrow(() -> MskAuthentication.validate(servers, false));
    }
  }
}
