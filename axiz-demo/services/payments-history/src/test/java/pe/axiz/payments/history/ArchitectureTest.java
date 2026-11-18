package pe.axiz.payments.history;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import pe.axiz.quality.archunit.Archetype;
import pe.axiz.quality.archunit.AxizArchitecture;
import pe.axiz.quality.archunit.CardDataScope;
import pe.axiz.quality.archunit.TokenizationScope;
import pe.axiz.quality.archunit.Extension;
import pe.axiz.quality.archunit.Stack;
import pe.axiz.quality.archunit.Structure;
import pe.axiz.spring.test.BlockHoundExtension;

/** Rules of the cqrs style plus the general axiz rules; breaking one fails the build. */
@ExtendWith(BlockHoundExtension.class)
class ArchitectureTest {

  @Test
  void followsTheArchitecture() {
    architecture().verify();
  }

  static AxizArchitecture architecture() {
    return AxizArchitecture.of("pe.axiz.payments.history")
        .archetype(Archetype.BUSINESS)
        .stack(Stack.REACTIVE)
        .structure(Structure.HEXAGONAL)
        .extensions(Extension.CQRS)
        .cardData(CardDataScope.NONE)
        .tokenization(TokenizationScope.NONE);
  }
}
