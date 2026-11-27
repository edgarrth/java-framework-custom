package pe.axiz.quality.containers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.testcontainers.utility.DockerImageName;

class TestContainerImagesTest {
  @Test
  void applicationImagesRemainUntouchedAndEveryReaperBuildInputChangesItsIdentity() {
    var original = DockerImageName.parse("registry.example.invalid/customer/service:1");
    assertEquals(original, new PatchedRyukImageSubstitutor().apply(original));
    var inputs = TestContainerImages.files("ryuk");
    assertTrue(
        inputs
            .keySet()
            .containsAll(List.of("Dockerfile", "go.mod", "go.sum", "sdk.patch", "LICENSE")));
    String name = TestContainerImages.imageName("ryuk", inputs);
    for (String key : inputs.keySet()) {
      var modified = new LinkedHashMap<>(inputs);
      modified.put(key, modified.get(key) + "\n");
      assertNotEquals(name, TestContainerImages.imageName("ryuk", modified));
    }
  }

  @Test
  void containerDefinitionsDoNotNeedDockerOrChooseApplicationCredentials() {
    try (var postgres = TestContainerImages.postgres();
        var valkey = TestContainerImages.valkey();
        var wiremock = TestContainerImages.wiremock();
        var keycloak = TestContainerImages.keycloak();
        var localstack = TestContainerImages.localstack();
        var kafka = TestContainerImages.kafka()) {
      assertEquals(List.of(5432), postgres.getExposedPorts());
      assertEquals(List.of(6379), valkey.getExposedPorts());
      assertEquals(List.of(8080), wiremock.getExposedPorts());
      assertFalse(wiremock.isRunning());
      assertEquals(List.of(8080), keycloak.getExposedPorts());
      assertFalse(keycloak.isRunning());
      assertEquals("test", postgres.getDatabaseName());
      assertEquals("test", postgres.getUsername());
      assertFalse(kafka.isRunning());
      assertFalse(localstack.isRunning());
      assertFalse(TestContainerImages.kafkaImage().isDone());
    }
  }

  @Test
  void buildContextsIncludeEveryLockedResourceAndPreserveSingleFileImageIdentity() {
    for (String recipe : List.of("ryuk", "localstack", "postgres", "kafka")) {
      var files = TestContainerImages.files(recipe);
      var image = TestContainerImages.image(recipe);
      assertEquals(files.keySet(), image.getTransferables().keySet());
      assertEquals(TestContainerImages.imageName(recipe, files), image.getDockerImageName());
      assertFalse(image.isDeleteOnExit());
    }
    assertEquals(
        "axiz-test/postgres:"
            + TestContainerImages.fingerprint(TestContainerImages.recipe("postgres")),
        TestContainerImages.imageName("postgres", TestContainerImages.files("postgres")));
    assertTrue(new PatchedRyukImageSubstitutor().getDescription().contains("Ryuk"));
  }

  @Test
  void everyRecipeHasAnImmutableBaseAndItsOwnContentAddress() {
    String postgres = TestContainerImages.recipe("postgres");
    String valkey = TestContainerImages.recipe("valkey");
    assertTrue(
        TestContainerImages.base(postgres).matches("postgres:17\\.12-alpine@sha256:[0-9a-f]{64}"));
    assertTrue(
        TestContainerImages.base(valkey)
            .matches("valkey/valkey:8\\.1\\.8-alpine@sha256:[0-9a-f]{64}"));
    assertNotEquals(
        TestContainerImages.fingerprint(postgres), TestContainerImages.fingerprint(valkey));
    assertNotEquals(
        TestContainerImages.fingerprint(postgres),
        TestContainerImages.fingerprint(postgres + "\n"));
    assertEquals(
        "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
        TestContainerImages.fingerprint("abc"));
    assertEquals("image:tag", TestContainerImages.base("# comment\nFROM image:tag \nRUN true\n"));
    assertThrows(IllegalArgumentException.class, () -> TestContainerImages.base("RUN true"));
    assertThrows(NullPointerException.class, () -> TestContainerImages.recipe("absent"));
  }
}
