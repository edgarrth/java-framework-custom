package pe.axiz.payments.ux;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import pe.axiz.quality.archunit.AxizArchitecture;
import pe.axiz.spring.test.BlockHoundExtension;

/** Rules of the bff style plus the general axiz rules; breaking one fails the build. */
@ExtendWith(BlockHoundExtension.class)
class ArchitectureTest {

  @Test
  void followsTheArchitecture() {
    AxizArchitecture.of("pe.axiz.payments.ux")
        .archetype(pe.axiz.quality.archunit.Archetype.UX)
        .stack(pe.axiz.quality.archunit.Stack.REACTIVE)
        .structure(pe.axiz.quality.archunit.Structure.BFF)
        .verify();
  }
}
