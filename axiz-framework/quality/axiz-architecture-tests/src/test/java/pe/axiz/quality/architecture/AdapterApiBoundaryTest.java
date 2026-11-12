package pe.axiz.quality.architecture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Test;
import pe.axiz.adapter.architecturefixture.AdapterApiFixture;
import pe.axiz.adapter.messaging.kafka.aws.AxizIamClientCallbackHandler;
import pe.axiz.adapter.messaging.kafka.fixture.UnapprovedAwsDependency;

class AdapterApiBoundaryTest {
  @Test
  void publicIdentityApiAndItsDeclarativeCompositionCannotExposeOrMutateTheContainer() {
    var importer = new ClassFileImporter();
    var valid =
        importer.importClasses(
            pe.axiz.spring.foundation.aws.AwsIdentity.class,
            pe.axiz.spring.foundation.aws.AxizAwsIdentityAutoConfiguration.class,
            pe.axiz.spring.foundation.aws.AwsAsyncResources.class);
    assertFalse(
        FrameworkArchitectureTest.AWS_IDENTITY_API_DOES_NOT_EXPOSE_CONTAINER
            .evaluate(valid)
            .hasViolation());
    assertFalse(
        FrameworkArchitectureTest.AWS_IDENTITY_HAS_NO_RUNTIME_DEFINITIONS_OR_CREATION_SWEEPS
            .evaluate(valid)
            .hasViolation());
    var invalid =
        importer.importClasses(pe.axiz.spring.foundation.aws.fixture.InvalidIdentityApi.class);
    assertTrue(
        FrameworkArchitectureTest.AWS_IDENTITY_API_DOES_NOT_EXPOSE_CONTAINER
            .evaluate(invalid)
            .hasViolation());
    assertTrue(
        FrameworkArchitectureTest.AWS_IDENTITY_HAS_NO_RUNTIME_DEFINITIONS_OR_CREATION_SWEEPS
            .evaluate(invalid)
            .hasViolation());
  }

  @Test
  void awsBaseRootAndIdentityBoundariesAreExplicit() {
    assertEquals("aws", TechnologyBoundaries.module("pe.axiz.adapter.aws"));
    assertEquals("aws", TechnologyBoundaries.module("pe.axiz.adapter.aws.identity"));
    assertEquals("aws", TechnologyBoundaries.technology("pe.axiz.adapter.aws"));
    assertEquals("aws", TechnologyBoundaries.technology("pe.axiz.adapter.aws.transport"));
    assertFalse(TechnologyBoundaries.allowed("aws", "aws", "dynamodb"));
    var importer = new ClassFileImporter();
    assertFalse(
        FrameworkArchitectureTest.ADAPTERS_ONLY_IMPLEMENT_THEIR_CONTRACT
            .evaluate(importer.importClasses(pe.axiz.adapter.aws.RootFixture.class))
            .hasViolation());
    assertTrue(
        FrameworkArchitectureTest.AWS_IDENTITY_DOES_NOT_KNOW_TRANSPORT
            .evaluate(
                importer.importClasses(pe.axiz.adapter.aws.identity.fixture.InvalidIdentity.class))
            .hasViolation());
    assertTrue(
        FrameworkArchitectureTest.AWS_BASE_HAS_NO_TELEMETRY_IMPLEMENTATION
            .evaluate(
                importer.importClasses(pe.axiz.adapter.aws.identity.fixture.TelemetryLeak.class))
            .hasViolation());
  }

  @Test
  void onlyTheMskAuthenticationBridgeMayShareTheAwsCredentialPolicy() {
    var importer = new ClassFileImporter();
    assertFalse(
        FrameworkArchitectureTest.ADAPTERS_ONLY_IMPLEMENT_THEIR_CONTRACT
            .evaluate(importer.importClasses(AxizIamClientCallbackHandler.class))
            .hasViolation());
    assertTrue(
        FrameworkArchitectureTest.ADAPTERS_ONLY_IMPLEMENT_THEIR_CONTRACT
            .evaluate(importer.importClasses(UnapprovedAwsDependency.class))
            .hasViolation());
  }

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
