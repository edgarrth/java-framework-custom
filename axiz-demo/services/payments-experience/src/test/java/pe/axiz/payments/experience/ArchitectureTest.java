package pe.axiz.payments.experience;

import org.junit.jupiter.api.Test;
import pe.axiz.quality.archunit.AxizArchitecture;

/** Rules of the bff style plus the general axiz rules; breaking one fails the build. */
class ArchitectureTest {

  @Test
  void followsTheArchitecture() {
    AxizArchitecture.verify("pe.axiz.payments.experience", AxizArchitecture.Style.BFF);
  }
}
