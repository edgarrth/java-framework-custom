package pe.axiz.quality.containers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class TestContainerImagesTest {
  @Test
  void containerDefinitionsDoNotNeedDockerOrChooseApplicationCredentials() {
    try (var postgres = TestContainerImages.postgres();
        var valkey = TestContainerImages.valkey();
        var wiremock = TestContainerImages.wiremock();
        var keycloak = TestContainerImages.keycloak();
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
      assertFalse(TestContainerImages.kafkaImage().isDone());
    }
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
