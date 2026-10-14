package pe.axiz.core.time;

import java.time.LocalDate;
import java.util.Objects;

/** Due-date calculation with business-day adjustment conventions. */
public final class DueDates {

  /** How to adjust a date that falls on a non-business day. */
  public enum Adjustment {
    /** Keep the date as is. */
    NONE,
    /** Move to the next business day. */
    FOLLOWING,
    /** Next business day unless it changes month, then previous business day. */
    MODIFIED_FOLLOWING,
    /** Move to the previous business day. */
    PRECEDING
  }

  private final BusinessCalendar calendar;

  public DueDates(BusinessCalendar calendar) {
    this.calendar = Objects.requireNonNull(calendar);
  }

  public LocalDate adjust(LocalDate date, Adjustment adjustment) {
    return switch (adjustment) {
      case NONE -> date;
      case FOLLOWING -> calendar.onOrAfter(date);
      case PRECEDING -> calendar.onOrBefore(date);
      case MODIFIED_FOLLOWING -> {
        LocalDate following = calendar.onOrAfter(date);
        yield following.getMonth() == date.getMonth() ? following : calendar.onOrBefore(date);
      }
    };
  }

  /** Due date after a number of calendar days, adjusted. */
  public LocalDate afterCalendarDays(LocalDate start, int days, Adjustment adjustment) {
    return adjust(start.plusDays(days), adjustment);
  }

  /** Due date after a number of business days. */
  public LocalDate afterBusinessDays(LocalDate start, int days) {
    return calendar.addBusinessDays(start, days);
  }

  /** Monthly due date on a day of month (clamped to month length), adjusted. */
  public LocalDate monthly(
      LocalDate start, int monthsAhead, int dayOfMonth, Adjustment adjustment) {
    LocalDate month = start.plusMonths(monthsAhead);
    int day = Math.min(dayOfMonth, month.lengthOfMonth());
    return adjust(month.withDayOfMonth(day), adjustment);
  }
}
