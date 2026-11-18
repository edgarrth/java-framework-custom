package pe.axiz.spring.observability;

import io.micrometer.common.KeyValue;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationFilter;
import pe.axiz.core.context.RequestContext;
import pe.axiz.core.context.RequestMetadata;
import pe.axiz.core.observability.AxizAttributes;

/**
 * Adds request context to every observation as high-cardinality attributes: they reach spans but
 * never become metric tags.
 */
public class RequestContextObservationFilter implements ObservationFilter {

  @Override
  public Observation.Context map(Observation.Context context) {
    RequestMetadata m = RequestContext.get();
    if (m == null) {
      return context;
    }
    context.addHighCardinalityKeyValue(
        KeyValue.of(AxizAttributes.CORRELATION_ID, m.correlationId()));
    if (m.channel() != null) {
      context.addHighCardinalityKeyValue(KeyValue.of(AxizAttributes.CHANNEL, m.channel()));
    }
    if (m.tenantId() != null) {
      context.addHighCardinalityKeyValue(KeyValue.of(AxizAttributes.TENANT, m.tenantId()));
    }
    return context;
  }
}
