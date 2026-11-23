package pe.axiz.core.time;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.Objects;

/**
 * Corte diario: las operaciones recibidas en el corte o después, o en un día no hábil, se procesan
 * el siguiente día hábil.
 *
 * @param cutoff hora local de corte en la zona del calendario
 * @param calendar calendario de días hábiles
 */
public record CutoffTime(LocalTime cutoff, BusinessCalendar calendar) {

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification =
          "BusinessCalendar is immutable: its holiday sets are copied and no mutators exist.")
  public CutoffTime {
    Objects.requireNonNull(cutoff, "cutoff");
    Objects.requireNonNull(calendar, "calendar");
  }

  @Override
  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP",
      justification =
          "BusinessCalendar is immutable: its holiday sets are copied and no mutators exist.")
  public BusinessCalendar calendar() {
    return calendar;
  }

  /**
   * Fecha hábil en la que se procesa una operación recibida en {@code receivedAt}.
   *
   * @param receivedAt instante de recepción
   * @return fecha de procesamiento
   */
  public LocalDate processingDate(Instant receivedAt) {
    ZonedDateTime local = receivedAt.atZone(calendar.zone());
    LocalDate date = local.toLocalDate();
    if (!calendar.isBusinessDay(date)) {
      return calendar.onOrAfter(date);
    }
    return local.toLocalTime().isBefore(cutoff) ? date : calendar.nextBusinessDay(date);
  }

  public boolean isBeforeCutoff(Instant receivedAt) {
    ZonedDateTime local = receivedAt.atZone(calendar.zone());
    return calendar.isBusinessDay(local.toLocalDate()) && local.toLocalTime().isBefore(cutoff);
  }
}
