package pe.axiz.payments.notifications;

import org.junit.jupiter.api.Test;
import pe.axiz.quality.archunit.ArchitectureSelfCheck;

/** Detecta una configuración de reglas de arquitectura vacía o deshabilitada. */
class ArchitectureSelfCheckTest {
  @Test
  void rejectsAnIntentionallyInvalidFixture() {
    ArchitectureSelfCheck.verify(ArchitectureTest.architecture());
  }
}
