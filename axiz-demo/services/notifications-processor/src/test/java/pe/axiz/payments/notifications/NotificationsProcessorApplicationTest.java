package pe.axiz.payments.notifications;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.localstack.LocalStackContainer;
import pe.axiz.spring.test.EphemeralSecrets;
import pe.axiz.spring.test.KafkaTestConfiguration;
import pe.axiz.spring.test.LocalStackTestConfiguration;

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
        var result =
            localstack.execInContainer(
                "awslocal", "sqs", "create-queue", "--queue-name", "axiz-demo-audit");
        if (result.getExitCode() != 0) {
          throw new IllegalStateException("audit queue not created: " + result.getStderr());
        }
      };
    }
  }

  @Test
  void startsAndReportsHealth(@Autowired MockMvc mvc) throws Exception {
    mvc.perform(get("/actuator/health")).andExpect(status().isOk());
  }
}
