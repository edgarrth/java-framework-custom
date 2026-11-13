package pe.axiz.core.time;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.Objects;

/**
 * Daily cut-off: operations received at or after the cut-off, or on a non-business day, are
 * processed on the next business day.
 *
 * @param cutoff local cut-off time in the calendar zone
 * @param calendar business calendar
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
   * Business date on which an operation received at {@code receivedAt} is processed.
   *
   * @param receivedAt reception instant
   * @return processing date
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
