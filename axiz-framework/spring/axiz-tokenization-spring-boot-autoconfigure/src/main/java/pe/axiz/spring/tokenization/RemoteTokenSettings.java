package pe.axiz.spring.tokenization;

import java.time.Duration;
import java.util.Locale;
import java.util.Set;
import pe.axiz.core.http.AuthType;
import pe.axiz.core.http.ClientSettings;
import pe.axiz.spring.httpclient.HttpClientRegistry;

/**
 * Se validan las invariantes del protocolo en el arranque antes de que pueda enviarse cualquier
 * solicitud sensible.
 */
final class RemoteTokenSettings {
  private RemoteTokenSettings() {}

  static ClientSettings validate(
      AxizTokenizationProperties properties, HttpClientRegistry registry) {
    var remote = properties.remote();
    if (remote == null) throw new IllegalStateException("Remote token client is required");
    var settings =
        registry
            .find(remote.client())
            .orElseThrow(() -> new IllegalStateException("Unknown token HTTP client"));
    if (settings.auth().type() != AuthType.TOKEN_RELAY
        && settings.auth().type() != AuthType.CLIENT_CREDENTIALS)
      throw new IllegalStateException("Token vault requires verified JWT bearer authentication");
    if (remote.asyncFacade() && settings.auth().type() == AuthType.TOKEN_RELAY)
      throw new IllegalStateException(
          "Token relay requires ReactiveTokenizer and subscriber identity; async facade requires"
              + " client credentials");
    if (settings.retry() != ClientSettings.RetryMode.NONE || settings.maxAttempts() != 1)
      throw new IllegalStateException("Token client requires retry=NONE and max-attempts=1");
    if (settings.maxConnections() > 256
        || settings.maxConcurrentCalls() < 1
        || settings.maxConcurrentCalls() > 256
        || settings.timeouts().connect().compareTo(Duration.ofSeconds(10)) > 0
        || settings.timeouts().read().compareTo(Duration.ofSeconds(30)) > 0
        || settings.timeouts().total().compareTo(Duration.ofSeconds(60)) > 0)
      throw new IllegalStateException(
          "Token client requires bounded connections, concurrency and timeouts");
    if (settings.baseUrl().getRawQuery() != null
        || !(settings.baseUrl().getRawPath().isEmpty()
            || "/".equals(settings.baseUrl().getRawPath())))
      throw new IllegalStateException("Token base URL must be an origin without path or query");
    var allowed = Set.of("x-correlation-id", "traceparent", "tracestate");
    if (settings.propagateHeaders().stream()
            .anyMatch(name -> !allowed.contains(name.toLowerCase(Locale.ROOT)))
        || registry.revealCardData(settings.name()))
      throw new IllegalStateException(
          "Token client forbids general sensitive serializers and non-observability propagated"
              + " headers");
    return settings;
  }
}
