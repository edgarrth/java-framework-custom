package pe.axiz.quality.architecture;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Test;
import pe.axiz.adapter.architecturefixture.AdapterApiFixture;

class AdapterApiBoundaryTest {
  @Test
  void protocolTypesAreRejectedInExportedSignaturesButAllowedInPrivateBridges() {
    var importer = new ClassFileImporter();
    var exported = importer.importClasses(AdapterApiFixture.class, AdapterApiFixture.Exposed.class);
    var type = exported.get(AdapterApiFixture.Exposed.class);
    assertTrue(
        FrameworkArchitectureTest.publiclyVisible(type),
        () -> type.getModifiers() + " enclosing=" + type.getEnclosingClass());
    assertTrue(
        FrameworkArchitectureTest.TECHNOLOGY_PUBLIC_API_USES_PORT_TYPES
            .evaluate(exported)
            .hasViolation(),
        () ->
            type.getMethods().stream()
                .map(m -> m.getFullName() + " " + m.getParameterTypes())
                .toList()
                .toString());
    var hidden =
        importer.importClasses(
            AdapterApiFixture.class, AdapterApiFixture.hidden(), AdapterApiFixture.nestedHidden());
    assertFalse(
        FrameworkArchitectureTest.TECHNOLOGY_PUBLIC_API_USES_PORT_TYPES
            .evaluate(hidden)
            .hasViolation());
  }
}
