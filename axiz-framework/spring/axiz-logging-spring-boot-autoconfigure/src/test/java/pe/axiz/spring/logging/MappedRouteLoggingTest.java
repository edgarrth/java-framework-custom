package pe.axiz.spring.logging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerResponse;
import pe.axiz.core.logging.HttpLogFormatter;
import pe.axiz.core.sensitive.ObjectMasker;

/**
 * Los metadatos reales de mapeo de MVC y de WebFlux funcional deben llegar al logger de acceso sin
 * IDs sin procesar.
 */
@ExtendWith(OutputCaptureExtension.class)
class MappedRouteLoggingTest {
  @RestController
  static class Customers {
    @GetMapping("/customers/{customerId}")
    String read() {
      return "ok";
    }
  }

  private static HttpLogFormatter formatter() {
    return new HttpLogFormatter(List.of(), 40, ObjectMasker.defaults());
  }

  private static AxizLoggingProperties.Http settings() {
    return new AxizLoggingProperties.Http(true, List.of(), false, 40, List.of(), 64);
  }

  @Test
  void mvcLogsTheMappedTemplateAndASafeMarkerForUnknownPaths(CapturedOutput output)
      throws Exception {
    String customerId = UUID.randomUUID().toString();
    String query = UUID.randomUUID().toString();
    var mvc =
        MockMvcBuilders.standaloneSetup(new Customers())
            .addFilters(new HttpLoggingFilter(formatter(), settings(), List.of()))
            .build();
    mvc.perform(get("/customers/{id}", customerId).queryParam("credential", query))
        .andExpect(status().isOk());
    mvc.perform(get("/missing/" + customerId)).andExpect(status().isNotFound());
    assertRoutes(output, customerId, query);
  }

  @Test
  void webfluxLogsTheFunctionalRouteTemplateAndASafeMarkerForUnknownPaths(CapturedOutput output) {
    String customerId = UUID.randomUUID().toString();
    String query = UUID.randomUUID().toString();
    var router =
        RouterFunctions.route()
            .GET("/customers/{customerId}", request -> ServerResponse.ok().bodyValue("ok"))
            .build();
    var client =
        WebTestClient.bindToRouterFunction(router)
            .webFilter(new HttpLoggingWebFilter(formatter(), settings()))
            .build();
    client
        .get()
        .uri(
            builder ->
                builder.path("/customers/{id}").queryParam("credential", query).build(customerId))
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody(String.class)
        .isEqualTo("ok");
    client
        .get()
        .uri("/missing/" + customerId)
        .exchange()
        .expectStatus()
        .isNotFound()
        .expectBody()
        .isEmpty();
    assertRoutes(output, customerId, query);
  }

  private static void assertRoutes(CapturedOutput output, String customerId, String query) {
    String access =
        output
            .getAll()
            .lines()
            .filter(line -> line.contains("http method="))
            .reduce("", (left, right) -> left + right + "\n");
    assertThat(access)
        .contains("path=/customers/{customerId}", "path=[unmatched]")
        .doesNotContain(customerId, query, "credential=");
  }
}
