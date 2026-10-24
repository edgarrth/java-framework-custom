package pe.axiz.core.sensitive;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class SensitiveTest {

  record Customer(
      String name,
      @Mask(MaskType.EMAIL) String email,
      @Sensitive(level = SensitivityLevel.HIGH, mask = MaskType.DOCUMENT) String document,
      @Sensitive(level = SensitivityLevel.CRITICAL) String password,
      Map<String, Object> extra,
      List<Card> cards) {}

  record Card(String pan, BigDecimal limit) {}

  static class Legacy {
    private final String token = "abc.def.ghi";
    private final String note = "hello";
  }

  @ParameterizedTest
  @CsvSource({
    "EMAIL, ana.perez@example.com, a********@example.com",
    "PHONE, +51987654321, *********321",
    "DOCUMENT, 12345678, ****5678",
    "CARD, 4111-1111-1111-1111, 411111******1111",
    "CARD, 123456, **3456",
    "PARTIAL, secret, s****t",
    "PARTIAL, ab, **",
    "NAME, Ana Torres, A** T*****",
    "NAME, María-José Pérez, M****-J*** P****",
    "FULL, anything, ****",
    "NONE, visible, visible",
    "EMAIL, invalid, i*****d"
  })
  void maskingStrategies(MaskType type, String input, String expected) {
    assertEquals(expected, Masker.mask(input, type));
  }

  @Test
  void policyPerChannel() {
    MaskingPolicy policy = MaskingPolicy.defaults();
    assertEquals(
        "****",
        policy.apply("1234", SensitivityLevel.CRITICAL, MaskType.NONE, OutputChannel.RESPONSE));
    assertEquals(
        "a**@x.pe",
        policy.apply("ana@x.pe", SensitivityLevel.MEDIUM, MaskType.EMAIL, OutputChannel.RESPONSE));
    assertEquals(
        "code",
        policy.apply("code", SensitivityLevel.LOW, MaskType.PARTIAL, OutputChannel.RESPONSE));
    assertEquals(
        "c**e", policy.apply("code", SensitivityLevel.LOW, MaskType.PARTIAL, OutputChannel.LOG));
    assertEquals(
        "c**e", policy.apply("code", SensitivityLevel.LOW, MaskType.NONE, OutputChannel.LOG));
    assertNull(policy.apply(null, SensitivityLevel.HIGH, MaskType.FULL, OutputChannel.LOG));
    assertEquals("****", Masker.mask(null, MaskType.NONE));
  }

  @Test
  @SuppressWarnings("unchecked")
  void objectMaskerAppliesAnnotationsAndKeyNames() {
    Customer c =
        new Customer(
            "Ana",
            "ana@example.com",
            "12345678",
            "p4ss",
            Map.of("cvv", "123", "phone", "+51987654321", "plain", 1),
            List.of(new Card("4111111111111111", new BigDecimal("1000.00"))));
    Map<String, Object> log =
        (Map<String, Object>) ObjectMasker.defaults().describe(c, OutputChannel.LOG);
    assertEquals("Ana", log.get("name"));
    assertEquals("a**@example.com", log.get("email"));
    assertEquals("****5678", log.get("document"));
    assertEquals("****", log.get("password"));
    Map<String, Object> extra = (Map<String, Object>) log.get("extra");
    assertEquals("****", extra.get("cvv"));
    assertEquals("*********321", extra.get("phone"));
    assertEquals(1, extra.get("plain"));
    Map<String, Object> card = ((List<Map<String, Object>>) log.get("cards")).get(0);
    assertEquals("411111******1111", card.get("pan"));
    assertEquals(new BigDecimal("1000.00"), card.get("limit"));
    Map<String, Object> legacy =
        (Map<String, Object>) ObjectMasker.defaults().describe(new Legacy(), OutputChannel.AUDIT);
    assertEquals("****", legacy.get("token"));
    assertEquals("hello", legacy.get("note"));
  }

  @Test
  void objectMaskerBoundsOutput() {
    Object deep =
        Map.of(
            "a", Map.of("b", Map.of("c", Map.of("d", Map.of("e", Map.of("f", Map.of("g", "x")))))));
    assertTrue(
        String.valueOf(ObjectMasker.defaults().describe(deep, OutputChannel.LOG)).contains("..."));
    List<Integer> many = java.util.stream.IntStream.range(0, 80).boxed().toList();
    List<?> described = (List<?>) ObjectMasker.defaults().describe(many, OutputChannel.LOG);
    assertEquals(51, described.size());
    assertEquals("<3 bytes>", ObjectMasker.defaults().describe(new byte[3], OutputChannel.LOG));
    String longText = "x".repeat(3000);
    assertTrue(
        String.valueOf(ObjectMasker.defaults().describe(longText, OutputChannel.LOG))
            .endsWith("(truncated)"));
  }

  @Test
  void jsonTextMaskerMasksNestedKeys() {
    String json =
        "{\"user\":{\"email\":\"ana@example.com\",\"password\":\"s3cr\\\"et\"},\"pan\":\"4111111111111111\","
            + "\"amount\":10.5,\"pin\":1234,\"ok\":true}";
    String masked = new JsonTextMasker(ObjectMasker.defaults()).mask(json, OutputChannel.LOG);
    assertTrue(masked.contains("\"email\":\"a**@example.com\""), masked);
    assertTrue(masked.contains("\"password\":\"****\""), masked);
    assertTrue(masked.contains("\"pan\":\"411111******1111\""), masked);
    assertTrue(masked.contains("\"pin\":\"****\""), masked);
    assertTrue(masked.contains("\"amount\":10.5"), masked);
    assertFalse(masked.contains("s3cr"));
  }

  @Test
  void aLongJsonValueIsMaskedWithoutExhaustingTheStack() {
    JsonTextMasker masker = new JsonTextMasker(ObjectMasker.defaults());
    String document = "QUJD".repeat(50_000);
    String json = "{\"document\":\"" + document + "\",\"password\":\"" + document + "\"}";
    String masked = masker.mask(json, OutputChannel.LOG);
    assertEquals("{\"document\":\"" + document + "\",\"password\":\"****\"}", masked);
  }

  @Test
  void onlyCompleteJsonPairsAreRead() {
    JsonTextMasker masker = new JsonTextMasker(ObjectMasker.defaults());
    assertEquals(
        "{\"pin\":\"****\",\"pin\":\"****\"x,\"pin\":\"****\".,\"ok\":false}",
        masker.mask(
            "{\"pin\":-12.5e+3,\"pin\" : 1E2x,\"pin\":7.,\"ok\":false}", OutputChannel.LOG));
    assertEquals(
        "{\"pin\":\"****\"}", masker.mask("{\"pin\":true}", OutputChannel.LOG), "a boolean value");
    String notPairs = "{\"pin\":-x, \"pin\" 1, \"p\\in\":1, \"\":1, \"pin\":null, \"pin\":\"open";
    assertEquals(notPairs, masker.mask(notPairs, OutputChannel.LOG));
    String longKey = "{\"" + "k".repeat(101) + "\":\"4111111111111111\"}";
    assertEquals(
        longKey, masker.mask(longKey, OutputChannel.LOG), "keys longer than 100 are skipped");
    String escaped = "{\"password\":\"a\\\"b\\\\\"}";
    assertEquals("{\"password\":\"****\"}", masker.mask(escaped, OutputChannel.LOG));
    String escapedLineBreak = "{\"password\":\"a\\\nb\"}";
    assertEquals(escapedLineBreak, masker.mask(escapedLineBreak, OutputChannel.LOG));
  }

  @Test
  void aKeyOfUpToOneHundredCharactersIsRead() {
    String key100 = "k".repeat(100);
    String key101 = "k".repeat(101);
    ObjectMasker masker =
        new ObjectMasker(
            MaskingPolicy.defaults(),
            SensitiveKeys.withExtra(
                Map.of(
                    key100,
                    new SensitiveField(key100, SensitivityLevel.HIGH, MaskType.DOCUMENT),
                    key101,
                    new SensitiveField(key101, SensitivityLevel.HIGH, MaskType.DOCUMENT))));
    JsonTextMasker json = new JsonTextMasker(masker);
    assertEquals(
        "{\"" + key100 + "\":\"*****6789\"}",
        json.mask("{\"" + key100 + "\":\"123456789\"}", OutputChannel.LOG));
    String skipped = "{\"" + key101 + "\":\"123456789\"}";
    assertEquals(skipped, json.mask(skipped, OutputChannel.LOG));
  }

  @Test
  void jsonWhitespaceAndEscapedLineBreaksFollowTheRules() {
    JsonTextMasker masker = new JsonTextMasker(ObjectMasker.defaults());
    for (String space : List.of(" ", "\t", "\n", "\u000B", "\f", "\r")) {
      assertEquals(
          "{\"pin\":\"****\"}",
          masker.mask("{\"pin\"" + space + ":" + space + "1234}", OutputChannel.LOG),
          "whitespace " + (int) space.charAt(0));
    }
    for (String lineBreak : List.of("\n", "\r", "\u0085", "\u2028", "\u2029")) {
      String text = "{\"pin\":\"12\\" + lineBreak + "34\"}";
      assertEquals(
          text, masker.mask(text, OutputChannel.LOG), "line break " + (int) lineBreak.charAt(0));
    }
    assertEquals("{\"pin\":\"****\"}", masker.mask("{\"pin\":\"12\\t34\"}", OutputChannel.LOG));
    assertEquals("{\"pin\":\"12\\", masker.mask("{\"pin\":\"12\\", OutputChannel.LOG));
    assertEquals("{\"pin\"", masker.mask("{\"pin\"", OutputChannel.LOG));
    assertEquals("{\"pin\":", masker.mask("{\"pin\":", OutputChannel.LOG));
    assertEquals("\"pin", masker.mask("\"pin", OutputChannel.LOG));
    assertEquals("{\"pin\":\"****\"}", masker.mask("{\"pin\":-0}", OutputChannel.LOG));
    assertEquals("{\"pin\":\"****\"e}", masker.mask("{\"pin\":12e}", OutputChannel.LOG));
    assertEquals("{\"pin\":\"****\"e+}", masker.mask("{\"pin\":12e+}", OutputChannel.LOG));
    assertEquals("{\"pin\":\"****\"}", masker.mask("{\"pin\":12e-3}", OutputChannel.LOG));
    assertEquals("{\"pin\":\"****\"}", masker.mask("{\"pin\":false}", OutputChannel.LOG));
    assertEquals("{\"pin\":fals}", masker.mask("{\"pin\":fals}", OutputChannel.LOG));
  }

  @Test
  void customKeys() {
    SensitiveKeys keys =
        SensitiveKeys.withExtra(
            Map.of(
                "customer-code",
                new SensitiveField("customer-code", SensitivityLevel.HIGH, MaskType.DOCUMENT)));
    ObjectMasker masker = new ObjectMasker(MaskingPolicy.defaults(), keys);
    assertEquals("*****6789", masker.maskByKey("CUSTOMER_CODE", "123456789", OutputChannel.LOG));
    assertEquals("free", masker.maskByKey("note", "free", OutputChannel.LOG));
  }
}
