package pe.axiz.quality.stores;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.testcontainers.DockerClientFactory;

/**
 * Las pruebas de integración necesitan Docker. En local se omiten si no está disponible; la CI pasa
 * {@code -Daxiz.it.requireDocker=true} para que la falta de Docker haga fallar el build en lugar de
 * ocultar las pruebas.
 */
final class Docker {

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
