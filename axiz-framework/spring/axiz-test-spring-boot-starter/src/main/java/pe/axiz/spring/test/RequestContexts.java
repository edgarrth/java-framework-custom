package pe.axiz.spring.test;

import java.util.Map;
import org.jspecify.annotations.Nullable;
import pe.axiz.core.context.RequestContext;
import pe.axiz.core.context.RequestMetadata;

/** Opens a request context in unit tests, as the web and security starters would. */
public final class RequestContexts {

  private RequestContexts() {}

  public static RequestContext.Scope open(
      @Nullable String clientId, @Nullable String subject, @Nullable String channel) {
    return RequestContext.open(
        RequestMetadata.builder("test-correlation-id", "test-request-id")
            .clientId(clientId)
            .subject(subject)
            .channel(channel)
            .attributes(Map.of())
            .build());
  }
}
