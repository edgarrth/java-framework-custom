package pe.axiz.spring.httpclient;

import io.micrometer.common.KeyValue;
import io.micrometer.common.KeyValues;
import org.springframework.web.reactive.function.client.ClientRequestObservationContext;
import org.springframework.web.reactive.function.client.DefaultClientRequestObservationConvention;

/** Uses the logical client name as {@code client.name} in metrics and spans of a WebClient. */
public class ReactiveClientNameObservationConvention
    extends DefaultClientRequestObservationConvention {

  private final String name;

  public ReactiveClientNameObservationConvention(String name) {
    this.name = name;
  }

  @Override
  public KeyValues getLowCardinalityKeyValues(ClientRequestObservationContext context) {
    return super.getLowCardinalityKeyValues(context).and(KeyValue.of("client.name", name));
  }
}
