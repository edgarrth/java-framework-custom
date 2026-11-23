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
 * Configuración base ({@code axiz.foundation.*}), normalmente en el ConfigMap {@code axiz-common}.
 *
 * @param zone zona horaria del negocio
 * @param calendar calendario de negocio
 * @param cutoff hora de corte diaria, si el servicio usa una
 */
@ConfigurationProperties("axiz.foundation")
public record AxizFoundationProperties(
    @DefaultValue("America/Lima") ZoneId zone,
    @DefaultValue Calendar calendar,
    @Nullable LocalTime cutoff) {

  /**
   * Definición del calendario de negocio.
   *
   * @param weekend días no laborables de la semana
   * @param recurringHolidays feriados que se repiten cada año, como {@code MM-dd}
   * @param holidays feriados puntuales
   */
  public record Calendar(
      @DefaultValue({"SATURDAY", "SUNDAY"}) List<DayOfWeek> weekend,
      @DefaultValue List<String> recurringHolidays,
      @DefaultValue List<LocalDate> holidays) {
    public Calendar {
      holidays = List.copyOf(holidays);
      recurringHolidays = List.copyOf(recurringHolidays);
      weekend = List.copyOf(weekend);
    }
  }
}
