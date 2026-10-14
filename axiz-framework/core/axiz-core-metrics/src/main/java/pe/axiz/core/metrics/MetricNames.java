package pe.axiz.core.metrics;

/** Names of the seven generic instruments (OpenTelemetry style; Prometheus adds suffixes). */
public final class MetricNames {

  public static final String COUNT = "axiz.events";
  public static final String AMOUNT = "axiz.events.amount";
  public static final String VALUE = "axiz.events.value";
  public static final String DURATION = "axiz.events.duration";
  public static final String ACTIVE = "axiz.events.active";
  public static final String INFLIGHT = "axiz.events.inflight";
  public static final String GAUGE = "axiz.events.gauge";

  private MetricNames() {}

  public static boolean isEventMetric(String meterName) {
    return meterName.equals(COUNT) || meterName.startsWith(COUNT + ".");
  }
}
