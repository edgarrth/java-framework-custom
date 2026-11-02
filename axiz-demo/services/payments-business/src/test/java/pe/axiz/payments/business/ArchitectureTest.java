package pe.axiz.payments.business;

import org.junit.jupiter.api.Test;
import pe.axiz.quality.archunit.AxizArchitecture;

/** Rules of the cqrs style plus the general axiz rules; breaking one fails the build. */
class ArchitectureTest {

  @Test
  void followsTheArchitecture() {
    AxizArchitecture.of("pe.axiz.payments.business").archetype(pe.axiz.quality.archunit.Archetype.BUSINESS).stack(pe.axiz.quality.archunit.Stack.SERVLET).structure(pe.axiz.quality.archunit.Structure.HEXAGONAL).extensions(pe.axiz.quality.archunit.Extension.CQRS).verify();
  }
}
