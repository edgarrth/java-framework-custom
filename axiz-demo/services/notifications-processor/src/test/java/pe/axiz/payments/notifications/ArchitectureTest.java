package pe.axiz.payments.notifications;

import org.junit.jupiter.api.Test;
import pe.axiz.quality.archunit.AxizArchitecture;
import pe.axiz.quality.archunit.Archetype;
import pe.axiz.quality.archunit.Stack;
import pe.axiz.quality.archunit.Structure;

/** Rules of the eda style plus the general axiz rules; breaking one fails the build. */
class ArchitectureTest {

  @Test
  void followsTheArchitecture() {
    AxizArchitecture.of("pe.axiz.payments.notifications")
        .archetype(Archetype.EVENT_PROCESSOR)
        .stack(Stack.SERVLET)
        .structure(Structure.EDA)
        .verify();
  }
}
