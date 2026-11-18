package pe.axiz.payments.process;

import org.junit.jupiter.api.Test;
import pe.axiz.quality.archunit.ArchitectureSelfCheck;

/** Detects an empty or disabled architecture rule configuration. */
class ArchitectureSelfCheckTest {
  @Test
  void rejectsAnIntentionallyInvalidFixture() {
    ArchitectureSelfCheck.verify(ArchitectureTest.architecture());
  }
}
