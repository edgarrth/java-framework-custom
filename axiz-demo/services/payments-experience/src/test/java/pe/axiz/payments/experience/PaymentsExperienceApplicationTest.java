package pe.axiz.payments.experience;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.reactive.server.WebTestClient;
import pe.axiz.spring.test.BlockHoundExtension;
import pe.axiz.spring.test.RedisTestConfiguration;

/**
 * Starts the whole service against real dependencies in containers (Testcontainers): wiring,
 * configuration, migrations and health. Needs Docker.
 */
@SpringBootTest
@AutoConfigureWebTestClient
@Import({RedisTestConfiguration.class})
@ExtendWith(BlockHoundExtension.class)
class PaymentsExperienceApplicationTest {

  @Test
  void startsAndReportsHealth(@Autowired WebTestClient client) {
    client.get().uri("/actuator/health").exchange().expectStatus().isOk();
  }
}
