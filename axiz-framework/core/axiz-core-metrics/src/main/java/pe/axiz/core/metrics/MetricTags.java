package pe.axiz.core.metrics;

/** Claves de tag estándar de los instrumentos genéricos de eventos. */
public final class MetricTags {

  public static final String EVENT = "event";
  public static final String CATEGORY = "category";
  public static final String OUTCOME = "outcome";
  public static final String REASON = "reason";
  public static final String CHANNEL = "channel";
  public static final String PRODUCT = "product";
  public static final String CURRENCY = "currency";
  public static final String STAGE = "stage";
  public static final String TENANT = "tenant";
  public static final String UNIT = "unit";

  /** Valor usado cuando un tag no aplica, para que todas las series tengan las mismas claves. */
  public static final String NONE = "none";

  /** Valor usado cuando un tag supera su límite de cardinalidad o no está permitido. */
  public static final String OTHER = "other";

  private MetricTags() {}
}
