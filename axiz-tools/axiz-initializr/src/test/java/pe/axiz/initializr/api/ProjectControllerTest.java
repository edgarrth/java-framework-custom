package pe.axiz.initializr.api;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import pe.axiz.initializr.config.InitializrProperties;
import pe.axiz.initializr.config.SecurityConfiguration;
import pe.axiz.initializr.service.GenerationService;
import pe.axiz.initializr.service.GenerationServiceTest;
import tools.jackson.databind.json.JsonMapper;

@WebMvcTest({ProjectController.class, MetadataController.class})
@Import({SecurityConfiguration.class, ApiExceptionHandler.class, ProjectControllerTest.Beans.class})
class ProjectControllerTest {

  @TestConfiguration
  @EnableConfigurationProperties(InitializrProperties.class)
  static class Beans {
    @Bean
    GenerationService generationService() {
      return new GenerationService(new SimpleMeterRegistry());
    }
  }

  @Autowired MockMvc mvc;
  @MockitoBean JwtDecoder jwtDecoder;
  @MockitoBean ClientRegistrationRepository clients;

  private final JsonMapper json = JsonMapper.builder().build();

  private String body(String archetype, String contract) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("name", "orders");
    m.put("groupId", "pe.axiz.orders");
    m.put("basePackage", "pe.axiz.orders");
    m.put("archetype", archetype);
    m.put("capabilities", List.of("cache"));
    m.put("openApi", contract);
    return json.writeValueAsString(m);
  }

  @Test
  void requiresAuthentication() throws Exception {
    mvc.perform(get("/v1/metadata")).andExpect(status().isUnauthorized());
  }

  @Test
  void servesTheCatalog() throws Exception {
    mvc.perform(get("/v1/metadata").with(jwt()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.archetypes[0].name").value("business"))
        .andExpect(
            jsonPath("$.capabilities[?(@.name == 'idempotency-redis')].requires[0]")
                .value("idempotency"));
  }

  @Test
  void generatesAZip() throws Exception {
    mvc.perform(
            post("/v1/projects")
                .with(jwt())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body("business", GenerationServiceTest.contract())))
        .andExpect(status().isOk())
        .andExpect(header().string("Content-Type", "application/zip"))
        .andExpect(header().string("Content-Disposition", "attachment; filename=\"orders.zip\""));
  }

  @Test
  void explainsWhyARequestCannotBeGenerated() throws Exception {
    mvc.perform(
            post("/v1/projects")
                .with(jwt())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body("business", null)))
        .andExpect(status().isUnprocessableContent())
        .andExpect(
            jsonPath("$.problems[0]")
                .value("Archetype business exposes REST: an OpenAPI 3.1 contract is required"));
  }

  @Test
  void rejectsMalformedRequests() throws Exception {
    mvc.perform(
            post("/v1/projects")
                .with(jwt())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"name\":\"../evil\",\"groupId\":\"x\",\"basePackage\":\"x\",\"archetype\":\"crud\"}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void validatesAndLints() throws Exception {
    mvc.perform(
            post("/v1/projects/validate")
                .with(jwt())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body("business", GenerationServiceTest.contract())))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.valid").value(true));
    mvc.perform(
            post("/v1/contracts/lint")
                .with(jwt())
                .contentType("application/yaml")
                .content(GenerationServiceTest.contract()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.valid").value(true));
  }
}
