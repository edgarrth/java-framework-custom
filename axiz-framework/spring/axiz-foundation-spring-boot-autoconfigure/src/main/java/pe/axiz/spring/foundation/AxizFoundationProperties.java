package pe.axiz.spring.foundation;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Foundation settings ({@code axiz.foundation.*}), normally in the {@code axiz-common} ConfigMap.
 *
 * @param zone business time zone
 * @param calendar business calendar
 * @param cutoff daily cut-off time, if the service uses one
 */
@ConfigurationProperties("axiz.foundation")
public record AxizFoundationProperties(
    @DefaultValue("America/Lima") ZoneId zone,
    @DefaultValue Calendar calendar,
    @Nullable LocalTime cutoff) {

  /**
   * Business calendar definition.
   *
   * @param weekend non-working days of the week
   * @param recurringHolidays holidays repeated every year, as {@code MM-dd}
   * @param holidays one-off holidays
   */
  public record Calendar(
      @DefaultValue({"SATURDAY", "SUNDAY"}) List<DayOfWeek> weekend,
      @DefaultValue List<String> recurringHolidays,
      @DefaultValue List<LocalDate> holidays) {}
}
