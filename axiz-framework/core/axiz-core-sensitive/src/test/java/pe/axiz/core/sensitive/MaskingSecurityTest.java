package pe.axiz.core.sensitive;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTimeout;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MaskingSecurityTest {
  private static final String PAN = "4111111111111111";
  private static final String MASKED = "411111******1111";

  @Test
  void encodedCloudEventDataIsOpaqueInLogsAndAudit() {
    String encoded = "NDExMTExMTExMTExMTExMQ==";
    for (var channel : java.util.List.of(OutputChannel.LOG, OutputChannel.AUDIT)) {
      var described =
          ObjectMasker.defaults().describe(java.util.Map.of("data_base64", encoded), channel);
      assertEquals(java.util.Map.of("data_base64", "****"), described);
    }
  }

  @Test
  void namesAreMatchedByWordsWithoutBroadSubstrings() {
    SensitiveKeys keys = SensitiveKeys.defaults();
    for (String name :
        List.of(
            "pan",
            "PAN",
            "cardPan",
            "card_pan",
            "cardNumber",
            "cvv2",
            "pinBlock",
            "idToken",
            "X-Api-Key",
            "newPassword",
            "password2",
            "track2",
            "clave")) {
      assertTrue(keys.find(name).isPresent(), name);
    }
    for (String name :
        List.of("span", "shipping", "tokenCount", "pinAttempts", "company", "expand")) {
      assertTrue(keys.find(name).isEmpty(), name);
    }
  }

  @Test
  void namedValuesAndCompositeSecretsNeverReachTheOutput() {
    ObjectMasker masker = ObjectMasker.defaults();
    for (OutputChannel channel : OutputChannel.values()) {
      assertEquals("****", masker.describeNamed("password", "p4ssword", null, channel));
      assertEquals(
          "****",
          masker.describe(Map.of("cvv", List.of("123")), channel) instanceof Map<?, ?> m
              ? m.get("cvv")
              : null);
      assertEquals("****", masker.describeNamed("pan", new String[] {PAN}, null, channel));
    }
    assertEquals(MASKED, masker.describeNamed("cardPan", PAN, null, OutputChannel.AUDIT));
    assertEquals(
        "****",
        masker.describeNamed(
            "neutral",
            "s3cret",
            new SensitiveField("neutral", SensitivityLevel.CRITICAL, MaskType.FULL),
            OutputChannel.LOG));
  }

  @Test
  void unknownKeysAndExactNumericRepresentationsAreDetected() {
    ObjectMasker masker = ObjectMasker.defaults();
    for (Object value :
        List.of(
            PAN,
            Long.valueOf(PAN),
            new BigInteger(PAN),
            new BigDecimal(PAN + ".0000"),
            new BigDecimal("4.111111111111111E15"),
            Double.valueOf(PAN))) {
      assertEquals(MASKED, masker.describe(value, OutputChannel.LOG), value.getClass().getName());
      assertEquals(MASKED, masker.describeNamed("unknown", value, null, OutputChannel.AUDIT));
    }
    JsonTextMasker json = new JsonTextMasker(masker);
    for (String value : List.of(PAN, PAN + ".0", "4.111111111111111E15", '"' + PAN + '"')) {
      assertEquals(
          "{\"unknown\":\"" + MASKED + "\"}",
          json.mask("{\"unknown\":" + value + "}", OutputChannel.LOG));
    }
    assertEquals(
        new BigDecimal(PAN + ".5"), masker.describe(new BigDecimal(PAN + ".5"), OutputChannel.LOG));
  }

  @Test
  void scannerHandlesBoundariesAndLimitsWork() {
    assertEquals("12 " + MASKED, PanDetector.redact("12 " + PAN));
    assertEquals(MASKED, PanDetector.redact("4111-1111-1111-1111"));
    assertEquals("1" + PAN + "111", PanDetector.redact("1" + PAN + "111"));
    assertTrue(PanDetector.containsPan("value=" + PAN));
    assertTimeout(
        Duration.ofSeconds(3), () -> PanDetector.redact("x 12345678901234567890 ".repeat(50_000)));
    BigDecimal extreme = new BigDecimal(BigInteger.ONE, Integer.MIN_VALUE);
    assertSame(extreme, PanDetector.redactNumber(extreme));
  }

  @Test
  void badValuesDoNotBreakLoggingAndKeyCollisionsAreVisible() {
    ObjectMasker masker = ObjectMasker.defaults();
    Map<Object, Object> map = new LinkedHashMap<>();
    map.put(1, "first");
    map.put("1", "second");
    ObjectMasker.Description result =
        masker.describeCounting("data", map, null, OutputChannel.AUDIT);
    assertEquals(Map.of("1", "<collision>"), result.value());
    assertEquals(1, result.sanitized());
    SelfMasking broken =
        () -> {
          throw new IllegalStateException("secret must never be logged");
        };
    result = masker.describeCounting("data", broken, null, OutputChannel.AUDIT);
    assertEquals("<unreadable>", result.value());
    assertEquals(1, result.sanitized());
    SelfMasking safe = () -> "safe";
    assertEquals("safe", masker.describe(safe, OutputChannel.RESPONSE));
  }
}
