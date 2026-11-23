package pe.axiz.core.time;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.MonthDay;
import java.time.ZoneId;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Calendario de días hábiles: días de fin de semana, feriados recurrentes (el mismo día cada año) y
 * feriados puntuales. Se construye a partir de la configuración (ConfigMap); no hay nada fijo en el
 * código.
 */
public final class BusinessCalendar {

  private static final int MAX_SEARCH_DAYS = 3660;

  private final ZoneId zone;
  private final Set<DayOfWeek> weekend;
  private final Set<MonthDay> recurringHolidays;
  private final Set<LocalDate> holidays;

  private BusinessCalendar(Builder b) {
    this.zone = b.zone;
    this.weekend = Set.copyOf(b.weekend);
    this.recurringHolidays = Set.copyOf(b.recurring);
    this.holidays = Set.copyOf(b.holidays);
    if (weekend.size() == DayOfWeek.values().length) {
      throw new IllegalArgumentException("A calendar needs at least one working day");
    }
  }

  public static Builder builder(ZoneId zone) {
    return new Builder(zone);
  }

  public ZoneId zone() {
    return zone;
  }

  public boolean isHoliday(LocalDate date) {
    return holidays.contains(date) || recurringHolidays.contains(MonthDay.from(date));
  }

  public boolean isBusinessDay(LocalDate date) {
    return !weekend.contains(date.getDayOfWeek()) && !isHoliday(date);
  }

  /** La misma fecha si es día hábil; en otro caso, la siguiente. */
  public LocalDate onOrAfter(LocalDate date) {
    LocalDate d = date;
    for (int i = 0; i < MAX_SEARCH_DAYS; i++) {
      if (isBusinessDay(d)) {
        return d;
      }
      d = d.plusDays(1);
    }
    throw new IllegalStateException("No business day found after " + date);
  }

  /** La misma fecha si es día hábil; en otro caso, la anterior. */
  public LocalDate onOrBefore(LocalDate date) {
    LocalDate d = date;
    for (int i = 0; i < MAX_SEARCH_DAYS; i++) {
      if (isBusinessDay(d)) {
        return d;
      }
      d = d.minusDays(1);
    }
    throw new IllegalStateException("No business day found before " + date);
  }

  public LocalDate nextBusinessDay(LocalDate date) {
    return onOrAfter(date.plusDays(1));
  }

  public LocalDate previousBusinessDay(LocalDate date) {
    return onOrBefore(date.minusDays(1));
  }

  /**
   * Suma (o resta, cuando es negativo) días hábiles; la fecha de inicio no se cuenta.
   *
   * @param date fecha de inicio
   * @param days días hábiles a sumar
   * @return día hábil resultante
   */
  public LocalDate addBusinessDays(LocalDate date, int days) {
    LocalDate d = date;
    int remaining = Math.abs(days);
    while (remaining > 0) {
      d = days > 0 ? nextBusinessDay(d) : previousBusinessDay(d);
      remaining--;
    }
    return d;
  }

  /** Días hábiles en {@code [from, to)}. */
  public long businessDaysBetween(LocalDate from, LocalDate to) {
    if (to.isBefore(from)) {
      return -businessDaysBetween(to, from);
    }
    long count = 0;
    for (LocalDate d = from; d.isBefore(to); d = d.plusDays(1)) {
      if (isBusinessDay(d)) {
        count++;
      }
    }
    return count;
  }

  /** Builder. */
  public static final class Builder {
    private final ZoneId zone;
    private final Set<DayOfWeek> weekend = EnumSet.of(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY);
    private final Set<MonthDay> recurring = new HashSet<>();
    private final Set<LocalDate> holidays = new HashSet<>();

    private Builder(ZoneId zone) {
      this.zone = Objects.requireNonNull(zone);
    }

    public Builder weekend(Set<DayOfWeek> days) {
      weekend.clear();
      weekend.addAll(days);
      return this;
    }

    public Builder recurringHoliday(MonthDay day) {
      recurring.add(day);
      return this;
    }

    public Builder holiday(LocalDate date) {
      holidays.add(date);
      return this;
    }

    public Builder holidays(Iterable<LocalDate> dates) {
      dates.forEach(holidays::add);
      return this;
    }

    public BusinessCalendar build() {
      return new BusinessCalendar(this);
    }
  }
}
