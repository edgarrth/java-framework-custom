package pe.axiz.quality.stores;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.testcontainers.DockerClientFactory;

/**
 * Integration tests need Docker. Locally they are skipped without it; CI passes {@code
 * -Daxiz.it.requireDocker=true} so a missing Docker fails the build instead of hiding the tests.
 */
final class Docker {

  /** AWS emulator; PostgreSQL and Valkey use the shared TestContainerImages recipes. */
  static final String LOCALSTACK = "localstack/localstack:4.9";

  private Docker() {}

  static void require() {
    if (DockerClientFactory.instance().isDockerAvailable()) {
      return;
    }
    if (Boolean.getBoolean("axiz.it.requireDocker")) {
      Assertions.fail("Docker is required (axiz.it.requireDocker=true) but is not available");
    }
    Assumptions.abort("Docker not available: store integration tests skipped");
  }
}
