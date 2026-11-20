package pe.axiz.payments.notifications;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.localstack.LocalStackContainer;
import pe.axiz.core.json.JsonWriter;
import pe.axiz.spring.test.EphemeralSecrets;
import pe.axiz.spring.test.KafkaTestConfiguration;
import pe.axiz.spring.test.LocalStackTestConfiguration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;

/**
 * Starts the whole service against real dependencies in containers (Testcontainers): wiring,
 * configuration, migrations and health. Needs Docker.
 */
@SpringBootTest(
    properties = {
      "axiz.config.environment=test",
      "axiz.messaging.kafka.authentication=configured",
      "axiz.transport.allow-plain-http=true",
      "axiz.transport.allow-insecure-data-stores=true"
    })
@AutoConfigureMockMvc
@Import({KafkaTestConfiguration.class, LocalStackTestConfiguration.class})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class NotificationsProcessorApplicationTest {
  @DynamicPropertySource
  static void secrets(DynamicPropertyRegistry registry) {
    EphemeralSecrets.register(registry, "field-key-v1");
  }

  /**
   * The audit queue the service consumes ({@code axiz.messaging.sqs.queues}), created in LocalStack
   * before the consumers start, as the infrastructure creates it in each environment.
   */
  @TestConfiguration(proxyBeanMethods = false)
  static class AuditQueue {

    @Bean
    InitializingBean axizDemoAuditQueue(LocalStackContainer localstack) {
      return () -> {
        // Provisioning includes the emulator's cold service startup. Runtime clients keep
        // their normal request budget; this separate bootstrap client closes before startup.
        try (var sqs =
            SqsClient.builder()
                .endpointOverride(localstack.getEndpoint())
                .region(Region.of(localstack.getRegion()))
                .credentialsProvider(
                    StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(
                            localstack.getAccessKey(), localstack.getSecretKey())))
                .overrideConfiguration(c -> c.apiCallTimeout(Duration.ofSeconds(60)))
                .build()) {
          String dlq = sqs.createQueue(b -> b.queueName("axiz-demo-audit-dlq")).queueUrl();
          String arn =
              sqs.getQueueAttributes(
                      b -> b.queueUrl(dlq).attributeNames(QueueAttributeName.QUEUE_ARN))
                  .attributes()
                  .get(QueueAttributeName.QUEUE_ARN);
          sqs.createQueue(
              b ->
                  b.queueName("axiz-demo-audit")
                      .attributes(
                          Map.of(
                              QueueAttributeName.REDRIVE_POLICY,
                              JsonWriter.write(
                                  Map.of("deadLetterTargetArn", arn, "maxReceiveCount", "5")))));
        }
      };
    }
  }

  @Test
  void startsAndReportsHealth(@Autowired MockMvc mvc) throws Exception {
    mvc.perform(get("/actuator/health")).andExpect(status().isOk());
  }
}
