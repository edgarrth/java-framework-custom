package pe.axiz.core.commons;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import pe.axiz.core.commons.net.DataTransportSecurityPolicy;

class DataTransportSecurityPolicyTest {
  @Test
  void parsesOnlyExplicitBooleansAndDefaultsToSecure() {
    for (String raw : new String[] {null, "true", "false", "TRUE", "FALSE"}) {
      var policy =
          DataTransportSecurityPolicy.from(
              key -> key.equals("axiz.config.environment") ? "test" : raw);
      assertEquals("true".equalsIgnoreCase(raw), policy.allowInsecureDataStores());
    }
    for (String invalid : new String[] {"", "yes", "1", " false ", "tru"}) {
      assertThrows(
          IllegalArgumentException.class, () -> DataTransportSecurityPolicy.from(key -> invalid));
    }
    assertThrows(NullPointerException.class, () -> DataTransportSecurityPolicy.from(null));
  }

  @Test
  void onlyAnExplicitSingleProcessExceptionAllowsInsecureConnections() {
    for (String environment : new String[] {"prod", "qa", "", "local", "test", "ci"}) {
      for (boolean allow : new boolean[] {false, true}) {
        var policy = new DataTransportSecurityPolicy(environment, allow);
        assertDoesNotThrow(() -> policy.requireVerified(true, true, "fixture"));
        if (allow && Environments.allowsInMemoryState(environment))
          assertDoesNotThrow(() -> policy.requireVerified(false, false, "fixture"));
        else {
          assertThrows(
              IllegalStateException.class, () -> policy.requireVerified(false, false, "fixture"));
          assertThrows(
              IllegalStateException.class, () -> policy.requireVerified(true, false, "fixture"));
        }
      }
    }
    assertThrows(
        IllegalStateException.class,
        () -> new DataTransportSecurityPolicy(null, true).requireVerified(false, false, "fixture"));
  }
}
