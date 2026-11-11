package pe.axiz.codegen;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import pe.axiz.codegen.model.FrameworkVersions;

class FrameworkVersionsTest {
  @Test
  void currentAndFutureCoordinatesDoNotEnablePreV2Generation() {
    for (String version : new String[] {"2.0.0", "2.1.3", "10.0.0", "2.1.0-SNAPSHOT"}) {
      assertTrue(FrameworkVersions.isSupported(version));
      FrameworkVersions.requireSupported(version);
    }
    for (String version :
        new String[] {"0.1.0", "1.0.0", "01.0.0", "2.0", "2.0.0-", "2.0.0-rc\n"}) {
      assertFalse(FrameworkVersions.isSupported(version));
      assertThrows(
          IllegalArgumentException.class, () -> FrameworkVersions.requireSupported(version));
    }
  }
}
