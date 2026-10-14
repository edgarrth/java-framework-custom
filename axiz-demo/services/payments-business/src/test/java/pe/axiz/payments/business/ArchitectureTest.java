package pe.axiz.payments.business;

import org.junit.jupiter.api.Test;
import pe.axiz.quality.archunit.AxizArchitecture;

/** Rules of the cqrs style plus the general axiz rules; breaking one fails the build. */
class ArchitectureTest {

  @Test
  void followsTheArchitecture() {
    AxizArchitecture.verify("pe.axiz.payments.business", AxizArchitecture.Style.CQRS);
  }
}
