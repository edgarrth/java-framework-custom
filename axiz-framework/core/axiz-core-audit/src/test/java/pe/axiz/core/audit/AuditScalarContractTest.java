package pe.axiz.core.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import org.junit.jupiter.api.Test;
import pe.axiz.core.json.JsonWriter;

class AuditScalarContractTest {
  @Test
  void supportedScalarsKeepTheirExactValueAndMutableNumbersAreCapturedOnce() {
    var integer = new AtomicInteger(7);
    var longValue = new AtomicLong(9);
    var adder = new LongAdder();
    adder.add(11);
    var source = new LinkedHashMap<String, Object>();
    List<Object> scalars =
        List.of(
            true,
            (byte) 3,
            (short) 4,
            5,
            6L,
            2.5d,
            1.25f,
            BigInteger.ONE.shiftLeft(255),
            new BigDecimal("9".repeat(64)),
            new BigDecimal(BigInteger.ONE, -64),
            new BigDecimal(BigInteger.ONE, 64));
    for (int i = 0; i < scalars.size(); i++) source.put("v" + i, scalars.get(i));
    source.put("integer", integer);
    source.put("long", longValue);
    source.put("adder", adder);
    var result = AuditDetails.freeze(source);
    assertEquals(0, result.sanitized());
    for (int i = 0; i < scalars.size(); i++)
      assertEquals(scalars.get(i), result.details().get("v" + i));
    integer.set(99);
    longValue.set(99);
    adder.add(99);
    assertEquals(7, result.details().get("integer"));
    assertEquals(9L, result.details().get("long"));
    assertEquals(11L, result.details().get("adder"));
    for (Object unsupported :
        List.of(
            Double.NaN,
            Double.POSITIVE_INFINITY,
            Float.NaN,
            Float.NEGATIVE_INFINITY,
            BigInteger.ONE.shiftLeft(256),
            new BigDecimal("9".repeat(65)),
            new BigDecimal(BigInteger.ONE, -65),
            new BigDecimal(BigInteger.ONE, 65))) {
      var rejected = AuditDetails.freeze(Map.of("value", unsupported));
      assertEquals(Map.of("value", "<unsupported>"), rejected.details());
      assertEquals(1, rejected.sanitized());
    }
  }

  @Test
  void temporalIdentifiersEnumsAndArraysProduceStableJsonValues() {
    for (Object scalar :
        List.of(
            'x',
            UUID.fromString("00000000-0000-0000-0000-000000000001"),
            Instant.EPOCH,
            LocalDate.of(2026, 1, 1),
            LocalDateTime.of(2026, 1, 1, 1, 2),
            OffsetDateTime.parse("2026-01-01T00:00:00Z"),
            ZonedDateTime.parse("2026-01-01T00:00:00Z"),
            Duration.ofSeconds(1))) {
      var result = AuditDetails.freeze(Map.of("value", scalar));
      assertEquals(scalar.toString(), result.details().get("value"));
      assertEquals(0, result.sanitized());
    }
    int[] numbers = {1, 2, 3};
    Object[] objects = {"a", null, List.of(true)};
    var result = AuditDetails.freeze(Map.of("numbers", numbers, "objects", objects));
    numbers[0] = 99;
    objects[0] = "changed";
    assertEquals(List.of(1, 2, 3), result.details().get("numbers"));
    assertEquals(
        "{\"numbers\":[1,2,3],\"objects\":[\"a\",null,[true]]}",
        JsonWriter.writeCanonical(result.details()));
    assertEquals(0, result.sanitized());
  }

  @Test
  void textTruncationNeverSplitsSurrogatesAndNamesUseTheDocumentedInclusiveLimit() {
    for (String value : List.of("a".repeat(2000), "a".repeat(1998) + "😀")) {
      var result = AuditDetails.freeze(Map.of("k".repeat(128), value));
      assertEquals(Map.of("k".repeat(128), value), result.details());
      assertEquals(0, result.sanitized());
    }
    for (String value :
        List.of("a".repeat(2001), "a".repeat(1999) + "😀tail", "a".repeat(1999) + "x😀")) {
      var result = AuditDetails.freeze(Map.of("text", value));
      String text = (String) result.details().get("text");
      assertEquals(
          value.startsWith("a".repeat(2000))
              ? "a".repeat(2000)
              : value.substring(0, value.charAt(1999) == 'x' ? 2000 : 1999),
          text);
      assertFalse(Character.isHighSurrogate(text.charAt(text.length() - 1)));
      assertEquals(1, result.sanitized());
    }
    assertEquals(Map.of(), AuditDetails.freeze(Map.of("k".repeat(129), "drop")).details());
  }

  @Test
  void nodeAndTraversalBudgetsStopAtTheBoundaryAndHostileRootMapsFailClosed() {
    var source = new LinkedHashMap<String, Object>();
    for (int i = 0; i < 499; i++) source.put("v" + i, true);
    var allowed = AuditDetails.freeze(source);
    assertEquals(499, allowed.details().size());
    assertEquals(0, allowed.sanitized());
    source.put("overflow", true);
    var limited = AuditDetails.freeze(source);
    assertEquals(allowed.details(), limited.details());
    assertEquals(1, limited.sanitized());
    source.clear();
    for (int i = 0; i < 500; i++) source.put("k".repeat(129) + i, true);
    source.put("after-scan-budget", true);
    var invalidNames = AuditDetails.freeze(source);
    assertTrue(invalidNames.details().isEmpty());
    assertEquals(501, invalidNames.sanitized());
    var hostile =
        new AbstractMap<String, Object>() {
          @Override
          public Set<Entry<String, Object>> entrySet() {
            throw new IllegalStateException("sensitive");
          }
        };
    var rejected = AuditDetails.freeze(hostile);
    assertTrue(rejected.details().isEmpty());
    assertEquals(1, rejected.sanitized());
    var five = new ArrayList<Object>(List.of("leaf"));
    Object nested = five;
    for (int i = 0; i < 4; i++) nested = List.of(nested);
    var maximumDepth = AuditDetails.freeze(Map.of("value", nested));
    assertEquals(0, maximumDepth.sanitized());
    var tooDeep = AuditDetails.freeze(Map.of("value", List.of(nested)));
    assertEquals(1, tooDeep.sanitized());
    assertTrue(JsonWriter.writeCanonical(tooDeep.details()).contains("<truncated>"));
  }
}
