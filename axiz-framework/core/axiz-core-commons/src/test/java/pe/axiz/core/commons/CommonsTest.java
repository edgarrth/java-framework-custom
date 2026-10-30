package pe.axiz.core.commons;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class CommonsTest {

  @Test
  void metadataBuilderSnapshotsAttributesWhenTheyAreProvided() {
    Map<String, String> attributes = new HashMap<>(Map.of("country", "PE"));
    var builder = RequestMetadata.builder("correlation", "request").attributes(attributes);
    attributes.put("country", "CHANGED");
    assertEquals(Map.of("country", "PE"), builder.build().attributes());
  }

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
  void headerCatalogValidatesAndPropagates() {
    HeaderCatalog catalog =
        HeaderCatalog.of(
            List.of(
                HeaderDefinition.required("X-Channel").withPattern("[a-z]{2,10}"),
                HeaderDefinition.optional("X-Device-Id").notPropagated(),
                HeaderDefinition.optional("X-Session").asSensitive()));
    Map<String, String> headers = new HashMap<>();
    headers.put("X-Channel", "WEB");
    headers.put("X-Device-Id", "d1");
    headers.put("X-Session", "s1");
    assertEquals(List.of("Header X-Channel has an invalid format"), catalog.validate(headers::get));
    headers.put("X-Channel", "web");
    assertTrue(catalog.validate(headers::get).isEmpty());
    assertEquals(Map.of("X-Channel", "web", "X-Session", "s1"), catalog.propagated(headers::get));
    assertTrue(catalog.find("x-session").orElseThrow().sensitive());
    headers.remove("X-Channel");
    assertEquals(List.of("Header X-Channel is required"), catalog.validate(headers::get));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            HeaderCatalog.of(
                List.of(HeaderDefinition.optional("A"), HeaderDefinition.optional("a"))));
  }

  @Test
  void headerValueLengthIsBounded() {
    HeaderDefinition def = new HeaderDefinition("X-A", false, null, true, false, 3);
    assertEquals("Header X-A exceeds 3 characters", def.validate("abcd"));
    assertNull(def.validate(null));
  }

  @Test
  void neverLoggedHeadersAreCaseInsensitive() {
    assertTrue(StandardHeaders.isNeverLogged("AUTHORIZATION"));
    assertTrue(StandardHeaders.isNeverLogged("cookie"));
    assertFalse(StandardHeaders.isNeverLogged("X-Correlation-Id"));
  }

  @Test
  void pagination() {
    PageRequest request = new PageRequest(2, 10, Sort.parse(List.of("createdAt,desc;name")));
    assertEquals(20, request.offset());
    assertEquals(2, request.sort().orders().size());
    assertEquals(Sort.Direction.DESC, request.sort().orders().get(0).direction());
    assertEquals(Sort.Direction.ASC, request.sort().orders().get(1).direction());
    Page<Integer> page = Page.of(List.of(1, 2, 3), request, 25);
    assertEquals(3, page.metadata().totalPages());
    assertFalse(page.metadata().hasNext());
    assertTrue(PageMetadata.of(PageRequest.first(), -1).hasNext());
    assertEquals(List.of("1", "2", "3"), page.map(String::valueOf).content());
    assertEquals(3, request.next().page());
    assertThrows(IllegalArgumentException.class, () -> PageRequest.of(0, PageRequest.MAX_SIZE + 1));
    assertThrows(
        IllegalArgumentException.class, () -> Sort.by("name; drop table", Sort.Direction.ASC));
    assertEquals(0, PageResponse.from(Page.empty(PageRequest.first())).items().size());
  }

  @Test
  void requestMetadataIsImmutable() {
    Map<String, String> attrs = new HashMap<>(Map.of("X-Channel", "web"));
    RequestMetadata md =
        RequestMetadata.builder("c-1", "r-1")
            .channel("web")
            .clientId("app")
            .locale(Locale.forLanguageTag("es-PE"))
            .receivedAt(Instant.EPOCH)
            .attributes(attrs)
            .build();
    attrs.put("X-Other", "x");
    assertEquals(1, md.attributes().size());
    assertEquals(Optional.of("web"), md.attribute("X-Channel"));
    assertEquals("t1", md.withTenant("t1").tenantId());
    assertThrows(IllegalArgumentException.class, () -> RequestMetadata.builder("", "r").build());
    assertEquals("c-9", ApiResponse.of("x", "c-9").meta().get("correlationId"));
  }

  @Test
  void perProcessStateIsRejectedOutsideSingleProcessEnvironments() {
    assertTrue(Environments.allowsInMemoryState("local"));
    assertTrue(Environments.allowsInMemoryState(" CI "));
    assertFalse(Environments.allowsInMemoryState("prod"));
    assertFalse(Environments.allowsInMemoryState("default"));
    assertFalse(Environments.allowsInMemoryState(null));
    Environments.requireSingleProcess("test", "axiz.lock.store=memory", "redis", "r");
    IllegalStateException e =
        assertThrows(
            IllegalStateException.class,
            () ->
                Environments.requireSingleProcess(
                    "qa",
                    "axiz.lock.store=memory",
                    "redis or dynamodb",
                    "replicas do not share it"));
    assertTrue(e.getMessage().contains("axiz.config.environment=qa"));
    assertTrue(e.getMessage().contains("use redis or dynamodb"));
    IllegalStateException unset =
        assertThrows(
            IllegalStateException.class,
            () -> Environments.requireSingleProcess(null, "axiz.lock.store=memory", "redis", "r"));
    assertTrue(unset.getMessage().contains("axiz.config.environment is not set"));
  }
}
