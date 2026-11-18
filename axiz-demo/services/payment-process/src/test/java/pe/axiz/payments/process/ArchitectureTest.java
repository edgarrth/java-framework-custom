package pe.axiz.payments.process;

import org.junit.jupiter.api.Test;
import pe.axiz.quality.archunit.Archetype;
import pe.axiz.quality.archunit.AxizArchitecture;
import pe.axiz.quality.archunit.CardDataScope;
import pe.axiz.quality.archunit.Stack;
import pe.axiz.quality.archunit.Structure;
import pe.axiz.quality.archunit.TokenizationScope;

/** Rules of the hexagonal style plus the general axiz rules; breaking one fails the build. */
class ArchitectureTest {

  @Test
  void followsTheArchitecture() {
    architecture().verify();
  }

  static AxizArchitecture architecture() {
    return AxizArchitecture.of("pe.axiz.payments.process")
        .archetype(Archetype.BUSINESS)
        .stack(Stack.SERVLET)
        .structure(Structure.HEXAGONAL)
        .cardData(CardDataScope.NONE)
        .tokenization(TokenizationScope.NONE);
  }
}
