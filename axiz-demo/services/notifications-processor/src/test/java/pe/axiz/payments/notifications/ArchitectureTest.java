package pe.axiz.payments.notifications;

import org.junit.jupiter.api.Test;
import pe.axiz.quality.archunit.AxizArchitecture;

/** Rules of the eda style plus the general axiz rules; breaking one fails the build. */
class ArchitectureTest {

  @Test
  void followsTheArchitecture() {
    AxizArchitecture.of("pe.axiz.payments.notifications")
        .archetype(pe.axiz.quality.archunit.Archetype.EVENT_PROCESSOR)
        .stack(pe.axiz.quality.archunit.Stack.SERVLET)
        .structure(pe.axiz.quality.archunit.Structure.EDA)
        .verify();
  }
}
