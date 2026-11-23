package pe.axiz.core.time;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.LocalDate;
import java.util.Objects;

/** Cálculo de fechas de vencimiento con convenciones de ajuste por días hábiles. */
public final class DueDates {

  /** Forma de ajustar una fecha que cae en un día no hábil. */
  public enum Adjustment {
    /** Conserva la fecha tal cual. */
    NONE,
    /** Pasa al siguiente día hábil. */
    FOLLOWING,
    /** Siguiente día hábil salvo que cambie de mes; en ese caso, el día hábil anterior. */
    MODIFIED_FOLLOWING,
    /** Pasa al día hábil anterior. */
    PRECEDING
  }

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification =
          "BusinessCalendar owns defensive snapshots and exposes no mutator; SpotBugs does not "
              + "infer its immutability.")
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

  /** Fecha de vencimiento tras una cantidad de días calendario, ajustada. */
  public LocalDate afterCalendarDays(LocalDate start, int days, Adjustment adjustment) {
    return adjust(start.plusDays(days), adjustment);
  }

  /** Fecha de vencimiento tras una cantidad de días hábiles. */
  public LocalDate afterBusinessDays(LocalDate start, int days) {
    return calendar.addBusinessDays(start, days);
  }

  /** Fecha de vencimiento mensual en un día del mes (limitado a la longitud del mes), ajustada. */
  public LocalDate monthly(
      LocalDate start, int monthsAhead, int dayOfMonth, Adjustment adjustment) {
    LocalDate month = start.plusMonths(monthsAhead);
    int day = Math.min(dayOfMonth, month.lengthOfMonth());
    return adjust(month.withDayOfMonth(day), adjustment);
  }
}
