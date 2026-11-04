package pe.axiz.payments.settlement;

import org.junit.jupiter.api.Test;
import pe.axiz.quality.archunit.AxizArchitecture;

/** Rules of the batch style plus the general axiz rules; breaking one fails the build. */
class ArchitectureTest {

  @Test
  void followsTheArchitecture() {
    AxizArchitecture.of("pe.axiz.payments.settlement")
        .archetype(pe.axiz.quality.archunit.Archetype.BATCH)
        .stack(pe.axiz.quality.archunit.Stack.SERVLET)
        .structure(pe.axiz.quality.archunit.Structure.BATCH)
        .verify();
  }
}
