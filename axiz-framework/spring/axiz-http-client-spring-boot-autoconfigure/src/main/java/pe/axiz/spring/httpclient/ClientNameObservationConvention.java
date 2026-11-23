package pe.axiz.spring.httpclient;

import io.micrometer.common.KeyValue;
import io.micrometer.common.KeyValues;
import org.springframework.http.client.observation.ClientRequestObservationContext;
import org.springframework.http.client.observation.DefaultClientRequestObservationConvention;

/** Usa el nombre lógico del cliente como {@code client.name} en métricas y spans. */
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
