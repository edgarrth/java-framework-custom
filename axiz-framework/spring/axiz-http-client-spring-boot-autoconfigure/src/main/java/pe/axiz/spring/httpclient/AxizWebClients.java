package pe.axiz.spring.httpclient;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import org.springframework.http.codec.json.JacksonJsonDecoder;
import org.springframework.http.codec.json.JacksonJsonEncoder;
import org.springframework.web.reactive.function.client.WebClient;
import pe.axiz.core.http.ClientSettings;
import tools.jackson.databind.json.JsonMapper;

/**
 * WebClients programáticos propiedad del arranque que comparten credenciales con nombre y políticas
 * de resiliencia.
 */
public final class AxizWebClients implements AutoCloseable {
  private final HttpClientRegistry registry;
  private final Supplier<WebClient.Builder> builders;
  private final Supplier<JsonMapper> mappers;
  private final Map<String, NativeHttpResources> resources = new HashMap<>();
  private boolean closed;

  public AxizWebClients(
      HttpClientRegistry registry,
      Supplier<WebClient.Builder> builders,
      Supplier<JsonMapper> mappers) {
    this.registry = Objects.requireNonNull(registry);
    this.builders = Objects.requireNonNull(builders);
    this.mappers = Objects.requireNonNull(mappers);
  }

  /**
   * Estados y cabeceras sin procesar para un decodificador de protocolo acotado; los reintentos
   * deben ser explícitamente NONE.
   */
  public WebClient exchangeClient(String name) {
    var settings =
        registry.find(name).orElseThrow(() -> new IllegalArgumentException("Unknown HTTP client"));
    var builder = builders.get();
    configure(settings, builder, false);
    return builder.build();
  }

  /**
   * Configura un cliente declarativo con la traducción de errores estándar. Se invoca en el
   * bootstrap.
   */
  public void configure(ClientSettings settings, WebClient.Builder builder) {
    configure(settings, builder, true);
  }

  private synchronized void configure(
      ClientSettings settings, WebClient.Builder builder, boolean translateErrors) {
    if (closed) throw new IllegalStateException("HTTP client factory is closed");
    var filter =
        new AxizExchangeFilter(
            registry.resilience(settings),
            registry.asyncAuth(settings),
            registry.tenantHeader(),
            translateErrors);
    var mapper =
        CardDataClientMapper.forClient(mappers.get(), registry.revealCardData(settings.name()));
    var transport =
        resources.computeIfAbsent(
            settings.name(),
            name ->
                new NativeHttpResources(
                    settings,
                    settings.baseUrl(),
                    settings.maxConnections(),
                    registry.transport(settings).sslBundle()));
    builder
        .codecs(
            codecs -> {
              codecs.defaultCodecs().jacksonJsonEncoder(new JacksonJsonEncoder(mapper));
              codecs.defaultCodecs().jacksonJsonDecoder(new JacksonJsonDecoder(mapper));
            })
        .baseUrl(settings.baseUrl().toString())
        .clientConnector(transport.connector())
        .observationConvention(new ReactiveClientNameObservationConvention(settings.name()))
        .filter(filter);
  }

  @Override
  public synchronized void close() {
    if (closed) return;
    closed = true;
    RuntimeException failure = null;
    for (var resource : resources.values()) {
      try {
        resource.close();
      } catch (RuntimeException problem) {
        if (failure == null) failure = problem;
        else failure.addSuppressed(problem);
      }
    }
    resources.clear();
    if (failure != null) throw failure;
  }
}
