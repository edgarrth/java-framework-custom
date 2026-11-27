package pe.axiz.core.metrics;

import java.util.Locale;

/** Valores estándar del tag de resultado. */
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
