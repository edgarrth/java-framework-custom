package pe.axiz.core.sensitive;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
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
    List<Integer> many = IntStream.range(0, 80).boxed().toList();
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

  private static final JsonTextMasker JSON = new JsonTextMasker(ObjectMasker.defaults());

  private static String masked(String json) {
    return JSON.mask(json, OutputChannel.LOG);
  }

  @Test
  void escapedMemberNamesAreDecodedBeforeTheyAreCompared() {
    assertEquals("{\"\\u0070assword\":\"****\"}", masked("{\"\\u0070assword\":\"SECRET-1\"}"));
    assertEquals("{\"pass\\u0077ord\":\"****\"}", masked("{\"pass\\u0077ord\":\"SECRET-1\"}"));
    assertEquals("{\"note\\\"x\":\"free\"}", masked("{\"note\\\"x\":\"free\"}"));
    String longKey = "description" + "x".repeat(200);
    assertEquals(
        "{\"" + longKey + "\":\"free\"}",
        masked("{\"" + longKey + "\":\"free\"}"),
        "a long name that is not sensitive keeps its value");
  }

  @Test
  void anArrayOrObjectUnderASensitiveKeyIsHiddenWhole() {
    assertEquals("{\"password\":\"****\"}", masked("{\"password\":[\"SECRET-1\",\"SECRET-2\"]}"));
    assertEquals("{\"pin\":\"****\"}", masked("{\"pin\":{\"value\":\"1234\",\"n\":[1,2]}}"));
    assertEquals("{\"password\":\"****\"}", masked("{\"password\":[]}"));
    assertEquals(
        "{\"items\":[{\"password\":\"****\"},{\"note\":\"y\"},[1,true,null]]}",
        masked("{\"items\":[{\"password\":\"x\"},{\"note\":\"y\"},[1,true,null]]}"),
        "other arrays and objects are read member by member");
    assertEquals("{\"password\":null}", masked("{\"password\":null}"), "null hides nothing");
  }

  @Test
  void stringsAreDecodedBeforeTheyAreMasked() {
    assertEquals("{\"pan\":\"411111******1111\"}", masked("{\"pan\":\"4111\\u003111111111111\"}"));
    assertEquals(
        "{\"note\":\"a\\u0041\\n\\/\"}",
        masked("{\"note\":\"a\\u0041\\n\\/\"}"),
        "values that are not masked keep their escapes");
    assertEquals("{\"pin\":\"****\"}", masked("{\"pin\":\"12\\t34\"}"));
  }

  @Test
  void numbersBooleansAndWhitespaceFollowTheJsonGrammar() {
    for (String number : List.of("0", "-0", "1234", "-12.5e+3", "1E2", "7.25", "3e-7")) {
      assertEquals("{\"pin\":\"****\"}", masked("{\"pin\":" + number + "}"), number);
      assertEquals("{\"n\":" + number + "}", masked("{\"n\":" + number + "}"), number);
    }
    assertEquals("{\"pin\":\"****\",\"ok\":false}", masked("{\"pin\":true,\"ok\":false}"));
    assertEquals(
        "{\"pin\":\"****\",\"a\":[1,2]}",
        masked(" \t\r\n{ \"pin\" :\n1234 ,\t\"a\" : [ 1 , 2 ] }\n"),
        "insignificant whitespace is dropped");
    assertEquals("[]", masked("[]"));
    assertEquals("\"plain\"", masked("\"plain\""));
  }

  @Test
  void textThatIsNotOneCompleteJsonValueIsNeverReturned() {
    String secret = "SECRET-123";
    for (String text :
        List.of(
            "{\"password\":\"" + secret,
            "{\"password\":\"" + secret + "\"",
            "{\"password\":\"" + secret + "\"} trailing",
            "{\"note\":\"" + secret + "\"}{\"x\":1}",
            "{\"password\" \"" + secret + "\"}",
            "{\"password\":\"" + secret + "\",}",
            "[\"" + secret + "\",]",
            "{\"password\":\"" + secret + "\u0001\"}",
            "{\"password\":\"" + secret + "\\x\"}",
            "{\"password\":\"" + secret + "\\u12\"}",
            "{\"password\":\"" + secret + "\\",
            "{password:\"" + secret + "\"}",
            "{\"pin\":01,\"note\":\"" + secret + "\"}",
            "{\"pin\":1.,\"note\":\"" + secret + "\"}",
            "{\"pin\":-,\"note\":\"" + secret + "\"}",
            "{\"pin\":1e,\"note\":\"" + secret + "\"}",
            "{\"pin\":fals,\"note\":\"" + secret + "\"}",
            "{\u000B\"note\":\"" + secret + "\"}",
            "",
            "   ")) {
      assertEquals(JsonTextMasker.UNREADABLE, masked(text), text);
      assertTrue(JSON.tryMask(text, OutputChannel.LOG).isEmpty(), text);
    }
  }

  @Test
  void nestingIsReadUpToTheLimit() {
    int max = JsonTextMasker.MAX_DEPTH;
    String deepest = "[".repeat(max) + "]".repeat(max);
    assertEquals(deepest, masked(deepest));
    assertEquals(
        JsonTextMasker.UNREADABLE, masked("[".repeat(max + 1) + "]".repeat(max + 1)), "too deep");
    String hidden = "{\"password\":" + "[".repeat(max - 1) + "]".repeat(max - 1) + "}";
    assertEquals("{\"password\":\"****\"}", masked(hidden));
  }

  @Test
  void maskedValuesAreWrittenAsValidJsonStrings() {
    ObjectMasker masker =
        new ObjectMasker(
            MaskingPolicy.defaults(),
            SensitiveKeys.withExtra(
                Map.of(
                    "memo", new SensitiveField("memo", SensitivityLevel.HIGH, MaskType.PARTIAL))));
    String out =
        new JsonTextMasker(masker)
            .mask("{\"memo\":\"ab\\\"\\\\\\n\\u0001cdef\"}", OutputChannel.LOG);
    assertTrue(out.startsWith("{\"memo\":\""), out);
    assertTrue(new JsonTextMasker(masker).tryMask(out, OutputChannel.LOG).isPresent(), out);
    assertFalse(out.contains("ab"), out);
  }

  @Test
  void keysThatAChannelShowsKeepTheirValues() {
    ObjectMasker masker =
        new ObjectMasker(
            MaskingPolicy.defaults(),
            SensitiveKeys.withExtra(
                Map.of(
                    "segment",
                    new SensitiveField("segment", SensitivityLevel.LOW, MaskType.FULL))));
    JsonTextMasker json = new JsonTextMasker(masker);
    assertEquals(
        "{\"segment\":[\"retail\"]}",
        json.mask("{\"segment\":[\"retail\"]}", OutputChannel.RESPONSE),
        "low sensitivity is shown in responses");
    assertEquals(
        "{\"segment\":\"****\"}",
        json.mask("{\"segment\":[\"retail\"]}", OutputChannel.LOG),
        "and masked in logs");
    assertFalse(masker.masksKey("segment", OutputChannel.RESPONSE));
    assertTrue(masker.masksKey("segment", OutputChannel.LOG));
    assertFalse(ObjectMasker.defaults().masksKey("note", OutputChannel.LOG));
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
