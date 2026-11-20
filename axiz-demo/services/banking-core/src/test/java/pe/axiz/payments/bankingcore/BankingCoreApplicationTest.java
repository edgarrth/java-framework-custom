package pe.axiz.payments.bankingcore;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.localstack.LocalStackContainer;
import pe.axiz.spring.test.LocalStackTestConfiguration;
import pe.axiz.spring.test.RedisTestConfiguration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;

/**
 * Starts the whole service against real dependencies in containers (Testcontainers): wiring,
 * configuration, migrations and health. Needs Docker.
 */
@SpringBootTest(
    properties = {
      "axiz.config.environment=test",
      "axiz.transport.allow-plain-http=true",
      "axiz.transport.allow-insecure-data-stores=true"
    })
@AutoConfigureMockMvc
@Import({
  RedisTestConfiguration.class,
  LocalStackTestConfiguration.class,
  BankingCoreApplicationTest.AuditQueue.class
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class BankingCoreApplicationTest {
  @TestConfiguration(proxyBeanMethods = false)
  static class AuditQueue {
    @Bean(destroyMethod = "close")
    SqsClient demoAuditSqs(LocalStackContainer localstack) {
      return SqsClient.builder()
          .endpointOverride(localstack.getEndpoint())
          .region(Region.of(localstack.getRegion()))
          .credentialsProvider(
              StaticCredentialsProvider.create(
                  AwsBasicCredentials.create(localstack.getAccessKey(), localstack.getSecretKey())))
          .build();
    }

    @Bean
    DynamicPropertyRegistrar demoAuditDestination(SqsClient sqs) {
      String url = sqs.createQueue(request -> request.queueName("demo-audit")).queueUrl();
      return registry -> registry.add("axiz.audit.sqs.queue-url", () -> url);
    }
  }

  @Test
  void startsAndReportsHealth(@Autowired MockMvc mvc) throws Exception {
    mvc.perform(get("/actuator/health")).andExpect(status().isOk());
  }
}
