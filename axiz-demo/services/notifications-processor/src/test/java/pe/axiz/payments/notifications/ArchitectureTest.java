package pe.axiz.payments.notifications;

import org.junit.jupiter.api.Test;
import pe.axiz.quality.archunit.Archetype;
import pe.axiz.quality.archunit.AxizArchitecture;
import pe.axiz.quality.archunit.CardDataScope;
import pe.axiz.quality.archunit.Stack;
import pe.axiz.quality.archunit.Structure;
import pe.axiz.quality.archunit.TokenizationScope;

/** Reglas del estilo eda más las reglas generales de axiz; incumplir una hace fallar el build. */
class ArchitectureTest {

  @Test
  void followsTheArchitecture() {
    architecture().verify();
  }

  static AxizArchitecture architecture() {
    return AxizArchitecture.of("pe.axiz.payments.notifications")
        .archetype(Archetype.EVENT_PROCESSOR)
        .stack(Stack.SERVLET)
        .structure(Structure.EDA)
        .cardData(CardDataScope.NONE)
        .tokenization(TokenizationScope.NONE);
  }
}
