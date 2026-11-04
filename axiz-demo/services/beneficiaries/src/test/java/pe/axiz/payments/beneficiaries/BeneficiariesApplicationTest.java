package pe.axiz.payments.beneficiaries;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import pe.axiz.spring.test.LocalStackTestConfiguration;

/**
 * Starts the whole service against real dependencies in containers (Testcontainers): wiring,
 * configuration, migrations and health. Needs Docker.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({LocalStackTestConfiguration.class})
class BeneficiariesApplicationTest {

  @Test
  void startsAndReportsHealth(@Autowired MockMvc mvc) throws Exception {
    mvc.perform(get("/actuator/health")).andExpect(status().isOk());
  }
}
