package pe.axiz.quality.stores;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.testcontainers.DockerClientFactory;

/**
 * Integration tests need Docker. Locally they are skipped without it; CI passes {@code
 * -Daxiz.it.requireDocker=true} so a missing Docker fails the build instead of hiding the tests.
 */
final class Docker {

  /**
   * Images of the approved infrastructure (same as the test starter); Renovate keeps them current.
   */
  static final String POSTGRES = "postgres:17.6-alpine";

  static final String VALKEY = "valkey/valkey:8.1-alpine";
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
