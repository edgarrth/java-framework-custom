package pe.axiz.core.sequence;

import java.time.ZoneId;
import java.util.Objects;
import pe.axiz.core.lang.Strings;

/**
 * Forma de presentar un valor de secuencia: {@code prefix + [period + separator] + zero-padded
 * value}, por ejemplo {@code OP-20260131-000123}.
 *
 * @param prefix prefijo fijo
 * @param width dígitos mínimos (rellenados con ceros)
 * @param reset política de reinicio (el periodo forma parte del texto cuando se reinicia)
 * @param separator separador entre las partes
 * @param zone zona usada para calcular el periodo
 */
public record SequenceFormat(
    String prefix, int width, ResetPolicy reset, String separator, ZoneId zone) {

  public SequenceFormat {
    Objects.requireNonNull(prefix);
    Objects.requireNonNull(reset);
    Objects.requireNonNull(separator);
    Objects.requireNonNull(zone);
    if (width < 1 || width > 19) {
      throw new IllegalArgumentException("width must be between 1 and 19");
    }
  }

  public String render(String period, long value) {
    String digits = Strings.leftPad(Long.toString(value), width, '0');
    if (digits.length() > width && reset != ResetPolicy.NEVER) {
      throw new SequenceExhaustedException(prefix, period);
    }
    return prefix + (period.isEmpty() ? "" : period + separator) + digits;
  }
}
