package pe.axiz.payments.orders;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import pe.axiz.spring.test.EphemeralSecrets;
import pe.axiz.spring.test.KafkaTestConfiguration;
import pe.axiz.spring.test.LocalStackTestConfiguration;
import pe.axiz.spring.test.PostgresTestConfiguration;

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
@Import({
  PostgresTestConfiguration.class,
  KafkaTestConfiguration.class,
  LocalStackTestConfiguration.class
})
class PaymentOrdersApplicationTest {

  /** Field encryption and blind index keys: random for every run, never committed. */
  @DynamicPropertySource
  static void secrets(DynamicPropertyRegistry registry) {
    EphemeralSecrets.register(registry, "field-key-v1", "blind-index-key");
  }

  @Test
  void startsAndReportsHealth(@Autowired MockMvc mvc) throws Exception {
    mvc.perform(get("/actuator/health")).andExpect(status().isOk());
  }
}
