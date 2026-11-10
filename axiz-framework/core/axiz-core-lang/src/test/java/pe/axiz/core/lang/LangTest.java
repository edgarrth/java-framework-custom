package pe.axiz.core.lang;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class LangTest {

  @Test
  void unicodeEncodingMustNotReplaceUnpairedSurrogates() {
    for (String valid :
        new String[] {
          "", "josé", "?", "\uD7FF\uE000", "\uD800\uDC00", "\uDBFF\uDFFF", "a\uD83D\uDE00z"
        }) {
      assertTrue(Strings.isWellFormedUnicode(valid));
    }
    for (String invalid :
        new String[] {
          "\uD800",
          "\uDBFF",
          "\uDC00",
          "\uDFFF",
          "\uD800a",
          "a\uDC00",
          "\uD800\uD800",
          "\uDC00\uD800",
          "\uD800\uDC00\uDC00"
        }) {
      assertFalse(Strings.isWellFormedUnicode(invalid));
    }
  }

  @Test
  void checksRejectInvalidArguments() {
    assertThrows(IllegalArgumentException.class, () -> Checks.notBlank(" ", "name"));
    assertThrows(IllegalArgumentException.class, () -> Checks.notNull(null, "name"));
    assertThrows(IllegalArgumentException.class, () -> Checks.positive(0, "n"));
    assertThrows(IllegalArgumentException.class, () -> Checks.positive(0L, "n"));
    assertThrows(IllegalArgumentException.class, () -> Checks.notNegative(-1, "n"));
    assertThrows(IllegalArgumentException.class, () -> Checks.notEmpty(List.of(), "l"));
    assertThrows(IllegalArgumentException.class, () -> Checks.maxLength("abcd", 3, "s"));
    assertThrows(
        IllegalArgumentException.class, () -> Checks.matches("x1", Pattern.compile("\\d+"), "s"));
    assertThrows(IllegalArgumentException.class, () -> Checks.argument(false, "msg"));
    assertEquals("ok", Checks.notBlank("ok", "name"));
    assertEquals(3, Checks.positive(3, "n"));
  }

  @Test
  void stringsHelpers() {
    assertEquals("pago-interbancario", Strings.slug("  Pago Interbancário!! "));
    assertEquals("Penarol Nandu", Strings.stripAccents("Peñarol Ñandú"));
    assertEquals("000123", Strings.leftPad("123", 6, '0'));
    assertEquals("12345", Strings.leftPad("12345", 3, '0'));
    assertEquals("ab  ", Strings.rightPad("ab", 4, ' '));
    assertEquals("abc", Strings.truncate("abcdef", 3));
    assertNull(Strings.trimToNull("   "));
    assertEquals("x", Strings.trimToNull(" x "));
    assertTrue(Strings.isBlank(null));
    assertTrue(Strings.hasText("a"));
  }

  @Test
  void datesParseWithAndWithoutOffset() {
    assertEquals(
        Optional.of(Instant.parse("2026-01-31T15:00:00Z")),
        Dates.parseInstant("2026-01-31T10:00:00-05:00"));
    assertEquals(
        Optional.of(Instant.parse("2026-01-31T10:00:00Z")),
        Dates.parseInstant("2026-01-31T10:00:00Z"));
    assertEquals(Optional.empty(), Dates.parseInstant("31/01/2026"));
    assertEquals(Optional.empty(), Dates.parseInstant(""));
    ZoneId lima = ZoneId.of("America/Lima");
    LocalDate day = LocalDate.of(2026, 3, 1);
    assertEquals(Instant.parse("2026-03-01T05:00:00Z"), Dates.startOfDay(day, lima));
    assertEquals(Instant.parse("2026-03-02T05:00:00Z"), Dates.endOfDayExclusive(day, lima));
    assertEquals(day, Dates.toLocalDate(Instant.parse("2026-03-02T04:59:59Z"), lima));
    assertEquals("2026-03-01T05:00:00Z", Dates.formatUtc(Dates.startOfDay(day, lima)));
  }

  @Test
  void preconditionsPreserveValidValuesAndRejectNullOrOutOfRangeValues() {
    Object value = new Object();
    assertEquals(value, Checks.notNull(value, "value"));
    assertThrows(IllegalArgumentException.class, () -> Checks.notBlank(null, "name"));
    assertThrows(IllegalArgumentException.class, () -> Checks.notEmpty(null, "items"));
    var items = List.of("one");
    assertEquals(items, Checks.notEmpty(items, "items"));
    Checks.argument(true, "valid");
    assertEquals(1L, Checks.positive(1L, "long"));
    assertEquals(0, Checks.notNegative(0, "zero"));
    assertEquals("abc", Checks.maxLength("abc", 3, "exact"));
    assertEquals("123", Checks.matches("123", Pattern.compile("[0-9]+"), "digits"));
  }

  @Test
  void stringBoundariesPreserveContentAndHandleMissingValues() {
    assertTrue(Strings.isBlank("  "));
    assertFalse(Strings.hasText("  "));
    assertFalse(Strings.isBlank("x"));
    assertNull(Strings.trimToNull(null));
    assertEquals("abc", Strings.truncate("abc", 3));
    assertEquals("", Strings.truncate("abc", 0));
    assertThrows(IllegalArgumentException.class, () -> Strings.truncate("abc", -1));
    assertEquals("abc", Strings.rightPad("abc", 2, '0'));
    assertEquals("abc", Strings.rightPad("abc", 3, '0'));
    assertEquals("abc", Strings.leftPad("abc", 3, '0'));
    assertEquals("", Strings.slug("!?"));
  }

  @Test
  void dayBoundsRespectDaylightSavingRatherThanAssumingTwentyFourHours() {
    ZoneId paris = ZoneId.of("Europe/Paris");
    LocalDate springChange = LocalDate.of(2026, 3, 29);
    assertEquals(
        23,
        Duration.between(
                Dates.startOfDay(springChange, paris), Dates.endOfDayExclusive(springChange, paris))
            .toHours());
    assertEquals(Optional.empty(), Dates.parseInstant(null));
    assertEquals(Instant.EPOCH.atOffset(ZoneOffset.UTC), Dates.toUtc(Instant.EPOCH));
  }
}
