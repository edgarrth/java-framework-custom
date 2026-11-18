package pe.axiz.spring.tokenization;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Explicit remote consumer or synthetic local double; selecting the capability requires a mode. */
@ConfigurationProperties("axiz.tokenization")
public record AxizTokenizationProperties(String mode, @Nullable Remote remote) {
  public AxizTokenizationProperties {
    if (!("remote".equals(mode) || "local".equals(mode)))
      throw new IllegalArgumentException("Tokenization requires mode=remote or mode=local");
    if ("remote".equals(mode) && remote == null)
      throw new IllegalArgumentException("Remote tokenization requires a named HTTP client");
  }

  /** Named authenticated client, with an optional Reactor-independent future facade. */
  public record Remote(String client, @DefaultValue("false") boolean asyncFacade) {
    public Remote {
      if (client == null || !client.matches("[a-z][a-z0-9-]{1,40}"))
        throw new IllegalArgumentException("Tokenization requires a valid HTTP client name");
    }
  }
}
