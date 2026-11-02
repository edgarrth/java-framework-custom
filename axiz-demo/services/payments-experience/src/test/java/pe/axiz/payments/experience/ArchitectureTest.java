package pe.axiz.payments.experience;

import org.junit.jupiter.api.Test;
import pe.axiz.quality.archunit.AxizArchitecture;

/** Rules of the bff style plus the general axiz rules; breaking one fails the build. */
class ArchitectureTest {

  @Test
  void followsTheArchitecture() {
    AxizArchitecture.of("pe.axiz.payments.experience").archetype(pe.axiz.quality.archunit.Archetype.UX).stack(pe.axiz.quality.archunit.Stack.REACTIVE).structure(pe.axiz.quality.archunit.Structure.BFF).verify();
  }
}
