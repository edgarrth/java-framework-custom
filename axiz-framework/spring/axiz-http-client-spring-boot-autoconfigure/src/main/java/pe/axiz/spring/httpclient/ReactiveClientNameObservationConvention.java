package pe.axiz.spring.httpclient;

import io.micrometer.common.KeyValue;
import io.micrometer.common.KeyValues;
import org.springframework.web.reactive.function.client.ClientRequestObservationContext;
import org.springframework.web.reactive.function.client.DefaultClientRequestObservationConvention;

/**
 * Usa el nombre lógico del cliente como {@code client.name} en las métricas y los spans de un
 * WebClient.
 */
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
