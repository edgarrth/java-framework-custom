package pe.axiz.payments.beneficiaries;

import org.junit.jupiter.api.Test;
import pe.axiz.quality.archunit.AxizArchitecture;

/** Rules of the layered style plus the general axiz rules; breaking one fails the build. */
class ArchitectureTest {

  @Test
  void followsTheArchitecture() {
    AxizArchitecture.verify("pe.axiz.payments.beneficiaries", AxizArchitecture.Style.LAYERED);
  }
}
