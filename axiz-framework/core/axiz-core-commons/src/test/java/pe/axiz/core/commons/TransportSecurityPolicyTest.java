package pe.axiz.core.commons;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.URI;
import java.util.Map;
import org.junit.jupiter.api.Test;
import pe.axiz.core.commons.net.TransportSecurityPolicy;

class TransportSecurityPolicyTest {
  @Test
  void productionRejectsPlaintextEvenOnLoopbackAndEvenWithFlag() {
    for (var policy :
        new TransportSecurityPolicy[] {
          TransportSecurityPolicy.secure(),
          new TransportSecurityPolicy("prod", true),
          new TransportSecurityPolicy("local-prod", true),
          new TransportSecurityPolicy(null, true),
          new TransportSecurityPolicy("local", false)
        })
      for (String uri :
          new String[] {
            "http://localhost", "http://127.0.0.1", "http://169.254.169.254", "http://private.bank"
          })
        assertThrows(
            IllegalArgumentException.class, () -> policy.validate(URI.create(uri), "endpoint"));
  }

  @Test
  void explicitExceptionIsRestrictedToWholeEnvironmentNames() {
    for (String env : new String[] {"local", "test", "ci", " LOCAL "})
      assertDoesNotThrow(
          () ->
              new TransportSecurityPolicy(env, true)
                  .validate(URI.create("http://emulator:4566"), "endpoint"));
    assertDoesNotThrow(
        () ->
            TransportSecurityPolicy.secure()
                .validate(URI.create("https://bank.invalid:8443/path?operation=x"), "endpoint"));
  }

  @Test
  void rejectsMalformedDestinationsWithoutIncludingSecretInMessages() {
    for (String uri :
        new String[] {
          "/relative",
          "file:/etc/passwd",
          "https://user:secret@bank.invalid",
          "https://bank.invalid/#secret",
          "ftp://bank.invalid"
        }) {
      var failure =
          assertThrows(
              IllegalArgumentException.class,
              () -> TransportSecurityPolicy.secure().validate(URI.create(uri), "endpoint"));
      assertFalse(failure.getMessage().contains("secret"));
    }
  }

  @Test
  void authenticatedClientsCannotOverrideOriginEvenWithAbsoluteUrls() {
    var policy = TransportSecurityPolicy.secure();
    var base = URI.create("https://bank.invalid");
    assertDoesNotThrow(
        () ->
            policy.validateTarget(
                base, URI.create("https://BANK.invalid:443/payments"), true, "client"));
    for (String uri :
        new String[] {
          "https://attacker.invalid", "https://bank.invalid:444", "http://bank.invalid"
        })
      assertThrows(
          IllegalArgumentException.class,
          () -> policy.validateTarget(base, URI.create(uri), true, "client"));
    assertDoesNotThrow(
        () -> policy.validateTarget(base, URI.create("https://public.invalid"), false, "client"));
  }

  @Test
  void contextPolicyOverridesProcessTestSettingsAndRejectsAmbiguousBoolean() {
    var policy =
        TransportSecurityPolicy.from(
            Map.of("axiz.config.environment", "prod", TransportSecurityPolicy.SETTING, "false")
                ::get);
    assertEquals("prod", policy.environment());
    assertFalse(policy.allowPlainHttp());
    assertThrows(
        IllegalArgumentException.class,
        () -> TransportSecurityPolicy.from(Map.of(TransportSecurityPolicy.SETTING, "yes")::get));
  }
}
