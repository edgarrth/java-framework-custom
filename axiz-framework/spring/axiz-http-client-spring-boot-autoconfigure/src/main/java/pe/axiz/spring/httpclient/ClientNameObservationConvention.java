package pe.axiz.spring.httpclient;

import io.micrometer.common.KeyValue;
import io.micrometer.common.KeyValues;
import org.springframework.http.client.observation.ClientRequestObservationContext;
import org.springframework.http.client.observation.DefaultClientRequestObservationConvention;

/** Uses the logical client name as {@code client.name} in metrics and spans. */
public class ClientNameObservationConvention extends DefaultClientRequestObservationConvention {

  private final String name;

  public ClientNameObservationConvention(String name) {
    this.name = name;
  }

  @Override
  public KeyValues getLowCardinalityKeyValues(ClientRequestObservationContext context) {
    return super.getLowCardinalityKeyValues(context).and(KeyValue.of("client.name", name));
  }
}
