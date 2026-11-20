package pe.axiz.quality.containers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;

class PatchedContainersIT {
  @Test
  void localStackRunsWithoutRootAndCannotModifyItsCode() throws Exception {
    boolean available = DockerClientFactory.instance().isDockerAvailable();
    if (Boolean.getBoolean("axiz.it.requireDocker")) assertTrue(available, "Docker is required");
    Assumptions.assumeTrue(available);
    try (var localstack = TestContainerImages.localstack().withServices("dynamodb", "kms")) {
      localstack.start();
      assertEquals("1000", localstack.execInContainer("id", "-u").getStdout().strip());
      var immutable =
          localstack.execInContainer(
              "sh",
              "-c",
              "test ! -w /opt/code/localstack/localstack-core/localstack/config.py"
                  + " && test ! -w /usr/lib/localstack/dynamodb-local/2/DynamoDBLocal.jar");
      assertEquals(0, immutable.getExitCode(), immutable.getStderr());
      var tables = localstack.execInContainer("awslocal", "dynamodb", "list-tables");
      assertEquals(0, tables.getExitCode(), tables.getStderr());
      assertTrue(tables.getStdout().contains("TableNames"));
      var key =
          localstack.execInContainer(
              "awslocal",
              "kms",
              "create-key",
              "--query",
              "KeyMetadata.KeyState",
              "--output",
              "text");
      assertEquals(0, key.getExitCode(), key.getStderr());
      assertTrue(key.getStdout().contains("Enabled"));
    }
  }

  @Test
  void patchedImagesInitializeAndRunRealDatabaseOperations() throws Exception {
    boolean available = DockerClientFactory.instance().isDockerAvailable();
    if (Boolean.getBoolean("axiz.it.requireDocker")) assertTrue(available, "Docker is required");
    Assumptions.assumeTrue(available);
    try (var postgres = TestContainerImages.postgres();
        var valkey = TestContainerImages.valkey()) {
      postgres.start();
      valkey.start();
      var reapers =
          DockerClientFactory.instance().client().listContainersCmd().exec().stream()
              .filter(
                  container ->
                      Arrays.stream(container.getNames())
                          .anyMatch(name -> name.contains("testcontainers-ryuk-")))
              .toList();
      assertTrue(
          reapers.stream()
              .anyMatch(container -> container.getImage().startsWith("axiz-test/ryuk:")),
          "Resource cleanup must use the patched inspectable image");
      assertTrue(postgres.getDockerImageName().startsWith("axiz-test/postgres:"));
      assertTrue(valkey.getDockerImageName().startsWith("axiz-test/valkey:"));
      var identity = postgres.execInContainer("sh", "-c", "! command -v gosu && id -u");
      assertEquals(0, identity.getExitCode(), identity.getStderr());
      assertFalse(identity.getStdout().strip().equals("0"));
      assertEquals("postgres", postgres.execInContainer("id", "-un").getStdout().strip());
      assertEquals("valkey", valkey.execInContainer("id", "-un").getStdout().strip());
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
