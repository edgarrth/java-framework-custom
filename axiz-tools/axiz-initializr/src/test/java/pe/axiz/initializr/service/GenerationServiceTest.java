package pe.axiz.initializr.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import pe.axiz.codegen.GenerationException;
import pe.axiz.codegen.model.ProjectRequest;
import pe.axiz.codegen.model.Stack;
import pe.axiz.codegen.model.Structure;

public class GenerationServiceTest {

  private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
  private final GenerationService service = new GenerationService(meters);

  /** The sample contract of the tests. */
  public static String contract() throws IOException {
    try (InputStream in = GenerationServiceTest.class.getResourceAsStream("/orders.yaml")) {
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  private static ProjectRequest request(String contract) {
    return ProjectRequest.builder()
        .name("orders")
        .groupId("pe.axiz.orders")
        .basePackage("pe.axiz.orders")
        .description("")
        .archetype("business")
        .structure(Structure.HEXAGONAL)
        .stack(Stack.SERVLET)
        .persistence("aurora")
        .capabilities(List.of())
        .openApi(contract)
        .asyncApi(null)
        .frameworkVersion("2.0.0")
        .extensions(List.of())
        .build();
  }

  @Test
  void generatesAndCounts() throws IOException {
    assertThat(service.generate(request(contract())).files()).containsKey("pom.xml");
    assertThat(
            meters.get("axiz.initializr.generations").tag("outcome", "success").counter().count())
        .isEqualTo(1);
  }

  @Test
  void rejectsInvalidRequestsAndCountsThem() {
    assertThatThrownBy(() -> service.generate(request(null)))
        .isInstanceOf(GenerationException.class);
    assertThat(
            meters.get("axiz.initializr.generations").tag("outcome", "rejected").counter().count())
        .isEqualTo(1);
  }

  @Test
  void lintsContracts() throws IOException {
    assertThat(service.lint(contract())).noneMatch(f -> f.error());
  }
}
