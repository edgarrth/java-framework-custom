package pe.axiz.core.time;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.MonthDay;
import java.util.EnumSet;
import java.util.List;
import org.junit.jupiter.api.Test;

class TimeTest {

  // Calendario ficticio para pruebas: fines de semana, 1 de enero, 1 de mayo, 28-29 de julio
  // recurrentes y el puntual 2026-12-08
  private final BusinessCalendar calendar =
      BusinessCalendar.builder(TimeSettings.DEFAULT_ZONE)
          .recurringHoliday(MonthDay.of(1, 1))
          .recurringHoliday(MonthDay.of(5, 1))
          .recurringHoliday(MonthDay.of(7, 28))
          .recurringHoliday(MonthDay.of(7, 29))
          .holidays(List.of(LocalDate.of(2026, 12, 8)))
          .build();

  @Test
  void businessDays() {
    assertFalse(calendar.isBusinessDay(LocalDate.of(2026, 7, 28)));
    assertTrue(calendar.isHoliday(LocalDate.of(2027, 5, 1)));
    assertFalse(calendar.isBusinessDay(LocalDate.of(2026, 10, 10))); // sábado
    assertEquals(LocalDate.of(2026, 7, 30), calendar.nextBusinessDay(LocalDate.of(2026, 7, 27)));
    assertEquals(
        LocalDate.of(2026, 7, 27), calendar.previousBusinessDay(LocalDate.of(2026, 7, 30)));
    assertEquals(LocalDate.of(2026, 12, 9), calendar.onOrAfter(LocalDate.of(2026, 12, 8)));
    assertEquals(LocalDate.of(2026, 10, 9), calendar.onOrBefore(LocalDate.of(2026, 10, 11)));
    assertEquals(
        LocalDate.of(2026, 10, 14), calendar.addBusinessDays(LocalDate.of(2026, 10, 9), 3));
    assertEquals(
        LocalDate.of(2026, 10, 6), calendar.addBusinessDays(LocalDate.of(2026, 10, 9), -3));
    assertEquals(LocalDate.of(2026, 10, 9), calendar.addBusinessDays(LocalDate.of(2026, 10, 9), 0));
    assertEquals(
        5, calendar.businessDaysBetween(LocalDate.of(2026, 10, 5), LocalDate.of(2026, 10, 12)));
    assertEquals(
        -5, calendar.businessDaysBetween(LocalDate.of(2026, 10, 12), LocalDate.of(2026, 10, 5)));
  }

  @Test
  void customWeekendAndValidation() {
    BusinessCalendar sixDays =
        BusinessCalendar.builder(TimeSettings.DEFAULT_ZONE)
            .weekend(EnumSet.of(DayOfWeek.SUNDAY))
            .build();
    assertTrue(sixDays.isBusinessDay(LocalDate.of(2026, 10, 10)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            BusinessCalendar.builder(TimeSettings.DEFAULT_ZONE)
                .weekend(EnumSet.allOf(DayOfWeek.class))
                .build());
  }

  @Test
  void cutoffMovesLateOperationsToNextBusinessDay() {
    CutoffTime cutoff = new CutoffTime(LocalTime.of(15, 0), calendar);
    // Lima es UTC-5: 19:59Z son las 14:59 locales y 20:00Z son las 15:00 locales
    assertEquals(
        LocalDate.of(2026, 10, 9), cutoff.processingDate(Instant.parse("2026-10-09T19:59:00Z")));
    assertTrue(cutoff.isBeforeCutoff(Instant.parse("2026-10-09T19:59:00Z")));
    assertEquals(
        LocalDate.of(2026, 10, 12), cutoff.processingDate(Instant.parse("2026-10-09T20:00:00Z")));
    assertFalse(cutoff.isBeforeCutoff(Instant.parse("2026-10-09T20:00:00Z")));
    // sábado por la mañana -> lunes
    assertEquals(
        LocalDate.of(2026, 10, 12), cutoff.processingDate(Instant.parse("2026-10-10T14:00:00Z")));
    // las 02:00Z del día 10 siguen siendo el viernes a las 21:00 en Lima, después del corte ->
    // lunes
    assertEquals(
        LocalDate.of(2026, 10, 12), cutoff.processingDate(Instant.parse("2026-10-10T02:00:00Z")));
  }

  @Test
  void dueDates() {
    DueDates due = new DueDates(calendar);
    assertEquals(
        LocalDate.of(2026, 10, 12),
        due.afterCalendarDays(LocalDate.of(2026, 10, 1), 9, DueDates.Adjustment.FOLLOWING));
    assertEquals(
        LocalDate.of(2026, 10, 9),
        due.afterCalendarDays(LocalDate.of(2026, 10, 1), 9, DueDates.Adjustment.PRECEDING));
    assertEquals(
        LocalDate.of(2026, 10, 10),
        due.afterCalendarDays(LocalDate.of(2026, 10, 1), 9, DueDates.Adjustment.NONE));
    // 2026-10-31 es sábado: following pasa a noviembre y modified following se queda en octubre
    assertEquals(
        LocalDate.of(2026, 10, 30),
        due.adjust(LocalDate.of(2026, 10, 31), DueDates.Adjustment.MODIFIED_FOLLOWING));
    assertEquals(
        LocalDate.of(2026, 11, 2),
        due.adjust(LocalDate.of(2026, 10, 31), DueDates.Adjustment.FOLLOWING));
    assertEquals(
        LocalDate.of(2026, 10, 7),
        due.adjust(LocalDate.of(2026, 10, 7), DueDates.Adjustment.MODIFIED_FOLLOWING));
    assertEquals(
        LocalDate.of(2027, 2, 26),
        due.monthly(LocalDate.of(2026, 12, 15), 2, 31, DueDates.Adjustment.MODIFIED_FOLLOWING));
    assertEquals(LocalDate.of(2026, 10, 14), due.afterBusinessDays(LocalDate.of(2026, 10, 9), 3));
  }
}
