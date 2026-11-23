package pe.axiz.payments.ux;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.reactive.server.WebTestClient;
import pe.axiz.spring.test.BlockHoundExtension;
import pe.axiz.spring.test.RedisTestConfiguration;

/**
 * Arranca el servicio completo contra dependencias reales en contenedores (Testcontainers):
 * cableado, configuración, migraciones y salud. Requiere Docker.
 */
@SpringBootTest(
    properties = {
      "axiz.config.environment=test",
      "axiz.transport.allow-plain-http=true",
      "axiz.transport.allow-insecure-data-stores=true"
    })
@AutoConfigureWebTestClient
@Import({RedisTestConfiguration.class})
@ExtendWith(BlockHoundExtension.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PaymentsUxApplicationTest {
  @AfterAll
  static void noBlockingCallWasHiddenByRetryOrErrorHandling() {
    assertThat(BlockHoundExtension.detectedBlockingCalls()).isEmpty();
  }

  @Test
  void startsAndReportsHealth(@Autowired WebTestClient client) {
    client.get().uri("/actuator/health").exchange().expectStatus().isOk();
  }
}
