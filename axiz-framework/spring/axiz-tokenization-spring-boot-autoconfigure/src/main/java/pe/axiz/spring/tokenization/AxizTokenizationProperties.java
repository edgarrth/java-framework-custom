package pe.axiz.spring.tokenization;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Consumidor remoto explícito o doble local sintético; seleccionar la capacidad requiere un modo.
 */
@ConfigurationProperties("axiz.tokenization")
public record AxizTokenizationProperties(String mode, @Nullable Remote remote) {
  public AxizTokenizationProperties {
    if (!("remote".equals(mode) || "local".equals(mode)))
      throw new IllegalArgumentException("Tokenization requires mode=remote or mode=local");
    if ("remote".equals(mode) && remote == null)
      throw new IllegalArgumentException("Remote tokenization requires a named HTTP client");
  }

  /**
   * Cliente autenticado con nombre, con una fachada de futures opcional independiente de Reactor.
   */
  public record Remote(String client, @DefaultValue("false") boolean asyncFacade) {
    public Remote {
      if (client == null || !client.matches("[a-z][a-z0-9-]{1,40}"))
        throw new IllegalArgumentException("Tokenization requires a valid HTTP client name");
    }
  }
}
