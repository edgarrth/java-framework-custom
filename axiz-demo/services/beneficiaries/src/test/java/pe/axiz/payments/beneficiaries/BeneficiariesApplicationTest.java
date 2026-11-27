package pe.axiz.payments.beneficiaries;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import pe.axiz.spring.test.EphemeralSecrets;
import pe.axiz.spring.test.LocalStackTestConfiguration;

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
@AutoConfigureMockMvc
@Import({LocalStackTestConfiguration.class})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class BeneficiariesApplicationTest {

  @DynamicPropertySource
  static void secrets(DynamicPropertyRegistry registry) {
    EphemeralSecrets.register(registry, "cursor-key-v1", "field-key-v1");
  }

  @Test
  void startsAndReportsHealth(@Autowired MockMvc mvc) throws Exception {
    mvc.perform(get("/actuator/health")).andExpect(status().isOk());
  }
}
