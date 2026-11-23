package pe.axiz.core.sequence;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import org.jspecify.annotations.Nullable;

/** Momento en que una secuencia formateada vuelve a empezar desde 1. */
public enum ResetPolicy {
  NEVER(null),
  DAILY(DateTimeFormatter.BASIC_ISO_DATE),
  MONTHLY(DateTimeFormatter.ofPattern("yyyyMM")),
  YEARLY(DateTimeFormatter.ofPattern("yyyy"));

  private final @Nullable DateTimeFormatter format;

  ResetPolicy(@Nullable DateTimeFormatter format) {
    this.format = format;
  }

  public String period(LocalDate date) {
    return format == null ? "" : format.format(date);
  }
}
