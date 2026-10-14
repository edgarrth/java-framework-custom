package pe.axiz.core.commons;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/** Date helpers. The framework stores and exchanges instants in UTC (ISO-8601). */
public final class Dates {

  private static final DateTimeFormatter ISO_UTC = DateTimeFormatter.ISO_INSTANT;

  private Dates() {}

  public static String formatUtc(Instant instant) {
    return ISO_UTC.format(instant);
  }

  /**
   * Parses ISO-8601 instants with or without offset ("2026-01-31T10:00:00Z" or with "-05:00").
   *
   * @param value text to parse
   * @return the instant, empty when the value is blank or invalid
   */
  public static Optional<Instant> parseInstant(@Nullable String value) {
    if (Strings.isBlank(value)) {
      return Optional.empty();
    }
    try {
      return Optional.of(OffsetDateTime.parse(value).toInstant());
    } catch (DateTimeParseException e) {
      try {
        return Optional.of(Instant.parse(value));
      } catch (DateTimeParseException ignored) {
        return Optional.empty();
      }
    }
  }

  public static Instant startOfDay(LocalDate date, ZoneId zone) {
    return date.atStartOfDay(zone).toInstant();
  }

  public static Instant endOfDayExclusive(LocalDate date, ZoneId zone) {
    return date.plusDays(1).atStartOfDay(zone).toInstant();
  }

  public static LocalDate toLocalDate(Instant instant, ZoneId zone) {
    return instant.atZone(zone).toLocalDate();
  }

  public static OffsetDateTime toUtc(Instant instant) {
    return instant.atOffset(ZoneOffset.UTC);
  }
}
