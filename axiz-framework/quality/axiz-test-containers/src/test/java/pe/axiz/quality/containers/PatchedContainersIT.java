package pe.axiz.quality.containers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;

class PatchedContainersIT {
  @Test
  void patchedImagesInitializeAndRunRealDatabaseOperations() throws Exception {
    boolean available = DockerClientFactory.instance().isDockerAvailable();
    if (Boolean.getBoolean("axiz.it.requireDocker")) assertTrue(available, "Docker is required");
    Assumptions.assumeTrue(available);
    try (var postgres = TestContainerImages.postgres();
        var valkey = TestContainerImages.valkey()) {
      postgres.start();
      valkey.start();
      assertTrue(postgres.getDockerImageName().startsWith("axiz-test/postgres:"));
      assertTrue(valkey.getDockerImageName().startsWith("axiz-test/valkey:"));
      var identity =
          postgres.execInContainer("sh", "-c", "! command -v gosu && su-exec postgres id -u");
      assertEquals(0, identity.getExitCode(), identity.getStderr());
      assertFalse(identity.getStdout().strip().equals("0"));
      var query =
          postgres.execInContainer("psql", "-U", "test", "-d", "test", "-At", "-c", "SELECT 42");
      assertEquals(0, query.getExitCode(), query.getStderr());
      assertEquals("42", query.getStdout().strip());
      var redis =
          valkey.execInContainer("valkey-cli", "SET", "probe", "synthetic", "NX", "PX", "30000");
      assertEquals(0, redis.getExitCode(), redis.getStderr());
      assertEquals("OK", redis.getStdout().strip());
      assertEquals(
          "synthetic", valkey.execInContainer("valkey-cli", "GET", "probe").getStdout().strip());
    }
  }
}
