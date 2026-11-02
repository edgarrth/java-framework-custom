package pe.axiz.payments.process;

import org.junit.jupiter.api.Test;
import pe.axiz.quality.archunit.AxizArchitecture;

/** Rules of the hexagonal style plus the general axiz rules; breaking one fails the build. */
class ArchitectureTest {

  @Test
  void followsTheArchitecture() {
    AxizArchitecture.of("pe.axiz.payments.process").archetype(pe.axiz.quality.archunit.Archetype.BUSINESS).stack(pe.axiz.quality.archunit.Stack.SERVLET).structure(pe.axiz.quality.archunit.Structure.HEXAGONAL).verify();
  }
}
