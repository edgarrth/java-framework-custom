package pe.axiz.initializr.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;
import pe.axiz.codegen.model.Archetype;
import pe.axiz.codegen.model.Catalog;
import pe.axiz.codegen.model.ProjectRequest;
import pe.axiz.codegen.model.Stack;
import pe.axiz.codegen.model.Structure;

/**
 * Cómo una solicitud de la API se convierte en una solicitud para el motor: valores por defecto del
 * arquetipo y elecciones explícitas.
 */
class ProjectRequestBodyTest {

  @Test
  void capabilitiesCannotBeChangedAfterTheRequestIsAccepted() {
    var capabilities = new java.util.ArrayList<>(List.of("cache"));
    var request = body(null, null, null, null, capabilities, null);
    capabilities.clear();
    assertThat(request.capabilities()).containsExactly("cache");
    assertThatThrownBy(() -> request.capabilities().add("crypto"))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  static final Catalog CATALOG = Catalog.load();

  static ProjectRequestBody body(
      String description,
      String structure,
      String stack,
      String persistence,
      List<String> capabilities,
      String version) {
    return new ProjectRequestBody(
        "orders",
        "pe.axiz.orders",
        "pe.axiz.orders",
        description,
        "business",
        structure,
        stack,
        persistence,
        capabilities,
        "openapi: 3.1.0",
        null,
        version,
        null);
  }

  @Test
  void missingOrBlankChoicesTakeTheArchetypeDefaults() {
    Archetype business = CATALOG.archetype("business").orElseThrow();

    for (ProjectRequestBody b :
        List.of(body(null, null, null, null, null, null), body("", " ", "", "  ", null, null))) {
      ProjectRequest r = b.toRequest(CATALOG, "9.9.9");

      assertThat(r.description()).isEmpty();
      assertThat(r.structure()).isEqualTo(business.defaultStructure());
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
        body("Orders", "Layered", "Reactive", "dynamodb", List.of("cache"), "2.1.3")
            .toRequest(CATALOG, "9.9.9");

    assertThat(r.description()).isEqualTo("Orders");
    assertThat(r.structure()).isEqualTo(Structure.of("layered"));
    assertThat(r.stack()).isEqualTo(Stack.of("reactive"));
    assertThat(r.persistence()).isEqualTo("dynamodb");
    assertThat(r.capabilities()).containsExactly("cache");
    assertThat(r.frameworkVersion()).isEqualTo("2.1.3");
    assertThat(r.archetype()).isEqualTo("business");
  }

  @Test
  void anUnknownArchetypeIsAnInvalidRequest() {
    ProjectRequestBody b =
        new ProjectRequestBody(
            "x", "g", "p", null, "teleport", null, null, null, null, null, null, null, null);

    assertThatThrownBy(() -> b.toRequest(CATALOG, "2.0.0"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Unknown archetype teleport");
  }
}
