package pe.axiz.payments.coreintegration;

import org.junit.jupiter.api.Test;
import pe.axiz.quality.archunit.AxizArchitecture;

/** Rules of the hexagonal style plus the general axiz rules; breaking one fails the build. */
class ArchitectureTest {

  @Test
  void followsTheArchitecture() {
    AxizArchitecture.verify("pe.axiz.payments.coreintegration", AxizArchitecture.Style.HEXAGONAL);
  }
}
