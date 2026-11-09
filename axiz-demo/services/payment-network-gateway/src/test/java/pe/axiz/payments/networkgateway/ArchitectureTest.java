package pe.axiz.payments.networkgateway;

import org.junit.jupiter.api.Test;
import pe.axiz.quality.archunit.Archetype;
import pe.axiz.quality.archunit.AxizArchitecture;
import pe.axiz.quality.archunit.Stack;
import pe.axiz.quality.archunit.Structure;

/** Rules of the hexagonal style plus the general axiz rules; breaking one fails the build. */
class ArchitectureTest {

  @Test
  void followsTheArchitecture() {
    AxizArchitecture.of("pe.axiz.payments.networkgateway")
        .archetype(Archetype.GATEWAY)
        .stack(Stack.SERVLET)
        .structure(Structure.HEXAGONAL)
        .verify();
  }
}
