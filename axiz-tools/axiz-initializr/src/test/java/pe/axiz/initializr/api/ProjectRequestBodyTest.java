package pe.axiz.initializr.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;
import pe.axiz.codegen.model.Archetype;
import pe.axiz.codegen.model.Catalog;
import pe.axiz.codegen.model.Framework;
import pe.axiz.codegen.model.ProjectRequest;
import pe.axiz.codegen.model.Stack;

/** How an API request becomes an engine request: archetype defaults and explicit choices. */
class ProjectRequestBodyTest {

  static final Catalog CATALOG = Catalog.load();

  static ProjectRequestBody body(
      String description,
      String framework,
      String stack,
      String persistence,
      List<String> capabilities,
      String version) {
    return new ProjectRequestBody(
        "orders-business",
        "pe.axiz.orders",
        "pe.axiz.orders.business",
        description,
        "business",
        framework,
        stack,
        persistence,
        capabilities,
        "openapi: 3.1.0",
        null,
        version);
  }

  @Test
  void missingOrBlankChoicesTakeTheArchetypeDefaults() {
    Archetype business = CATALOG.archetype("business").orElseThrow();

    for (ProjectRequestBody b :
        List.of(body(null, null, null, null, null, null), body("", " ", "", "  ", null, null))) {
      ProjectRequest r = b.toRequest(CATALOG, "9.9.9");

      assertThat(r.description()).isEmpty();
      assertThat(r.framework()).isEqualTo(business.defaultFramework());
      assertThat(r.stack()).isEqualTo(business.defaultStack());
      assertThat(r.persistence()).isEqualTo(business.defaultPersistence());
      assertThat(r.capabilities()).isEmpty();
      assertThat(r.frameworkVersion()).isEqualTo("9.9.9");
      assertThat(r.openApi()).isEqualTo("openapi: 3.1.0");
      assertThat(r.asyncApi()).isNull();
    }
  }

  @Test
  void explicitChoicesAreKeptCaseInsensitively() {
    ProjectRequest r =
        body("Orders", "CQRS", "Reactive", "dynamodb", List.of("cache"), "1.2.3")
            .toRequest(CATALOG, "9.9.9");

    assertThat(r.description()).isEqualTo("Orders");
    assertThat(r.framework()).isEqualTo(Framework.of("cqrs"));
    assertThat(r.stack()).isEqualTo(Stack.of("reactive"));
    assertThat(r.persistence()).isEqualTo("dynamodb");
    assertThat(r.capabilities()).containsExactly("cache");
    assertThat(r.frameworkVersion()).isEqualTo("1.2.3");
    assertThat(r.archetype()).isEqualTo("business");
  }

  @Test
  void anUnknownArchetypeIsAnInvalidRequest() {
    ProjectRequestBody b =
        new ProjectRequestBody(
            "x", "g", "p", null, "teleport", null, null, null, null, null, null, null);

    assertThatThrownBy(() -> b.toRequest(CATALOG, "1.0.0"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Unknown archetype teleport");
  }
}
