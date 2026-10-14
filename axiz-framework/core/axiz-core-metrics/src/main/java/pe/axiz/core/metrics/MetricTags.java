package pe.axiz.core.metrics;

/** Standard tag keys of the generic event instruments. */
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

  /** Value used when a tag does not apply, so every series has the same keys. */
  public static final String NONE = "none";

  /** Value used when a tag exceeds its cardinality limit or is not allowed. */
  public static final String OTHER = "other";

  private MetricTags() {}
}
