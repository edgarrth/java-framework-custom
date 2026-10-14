package pe.axiz.core.metrics;

import java.util.Locale;

/** Standard outcome tag values. */
public enum Outcome {
  SUCCESS,
  REJECTED,
  ERROR,
  TIMEOUT,
  CANCELLED;

  public String tagValue() {
    return name().toLowerCase(Locale.ROOT);
  }
}
