package pe.axiz.adapter.aws.identity.fixture;

import io.micrometer.core.instrument.MeterRegistry;

public final class TelemetryLeak {
  private TelemetryLeak() {}

  public static Class<?> telemetry() {
    return MeterRegistry.class;
  }
}
