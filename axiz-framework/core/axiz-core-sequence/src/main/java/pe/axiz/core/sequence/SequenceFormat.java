package pe.axiz.core.sequence;

import java.time.ZoneId;
import java.util.Objects;
import pe.axiz.core.lang.Strings;

/**
 * How a sequence value is rendered: {@code prefix + [period + separator] + zero-padded value}, e.g.
 * {@code OP-20260131-000123}.
 *
 * @param prefix fixed prefix
 * @param width minimum digits (zero padded)
 * @param reset reset policy (the period is part of the text when it resets)
 * @param separator separator between parts
 * @param zone zone used to compute the period
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
