package pe.axiz.core.context;

import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import pe.axiz.core.lang.Checks;

/**
 * Metadatos inmutables de la petición o del mensaje en proceso.
 *
 * @param correlationId id compartido por todas las llamadas del mismo flujo de negocio
 * @param requestId id de esta petición en particular
 * @param channel canal de origen (web, móvil, etc.), valor libre declarado por el servicio
 * @param clientId aplicación que llama, normalmente tomada del token de acceso
 * @param subject usuario final, cuando existe
 * @param tenantId tenant, solo para servicios multi-tenant
 * @param issuer emisor del token de acceso que autenticó al llamador, cuando existe
 * @param locale locale preferido
 * @param receivedAt instante de recepción (UTC)
 * @param attributes cabeceras de negocio declaradas en el catálogo de cabeceras
 */
public record RequestMetadata(
    String correlationId,
    String requestId,
    @Nullable String channel,
    @Nullable String clientId,
    @Nullable String subject,
    @Nullable String tenantId,
    @Nullable String issuer,
    Locale locale,
    Instant receivedAt,
    Map<String, String> attributes) {

  public RequestMetadata {
    Checks.notBlank(correlationId, "correlationId");
    Checks.notBlank(requestId, "requestId");
    Checks.notNull(locale, "locale");
    Checks.notNull(receivedAt, "receivedAt");
    attributes = Map.copyOf(attributes);
  }

  public Optional<String> attribute(String name) {
    return Optional.ofNullable(attributes.get(name));
  }

  public RequestMetadata withTenant(String tenant) {
    return new RequestMetadata(
        correlationId,
        requestId,
        channel,
        clientId,
        subject,
        tenant,
        issuer,
        locale,
        receivedAt,
        attributes);
  }

  /**
   * Copia con el llamador autenticado; el tenant se reemplaza solo cuando el token trae uno.
   *
   * @param callerClientId aplicación que llama
   * @param callerSubject usuario final, si existe
   * @param callerTenant tenant del token, si existe
   * @return los metadatos enriquecidos
   */
  public RequestMetadata withCaller(
      @Nullable String callerClientId,
      @Nullable String callerSubject,
      @Nullable String callerTenant) {
    return withCaller(callerClientId, callerSubject, callerTenant, issuer);
  }

  /**
   * Copia con el llamador autenticado y el emisor de su token.
   *
   * @param callerClientId aplicación que llama
   * @param callerSubject usuario final, si existe
   * @param callerTenant tenant del token, si existe (en otro caso se conserva el actual)
   * @param callerIssuer emisor del token, si existe
   * @return los metadatos enriquecidos
   */
  public RequestMetadata withCaller(
      @Nullable String callerClientId,
      @Nullable String callerSubject,
      @Nullable String callerTenant,
      @Nullable String callerIssuer) {
    return new RequestMetadata(
        correlationId,
        requestId,
        channel,
        callerClientId,
        callerSubject,
        callerTenant != null ? callerTenant : tenantId,
        callerIssuer,
        locale,
        receivedAt,
        attributes);
  }

  /** Builder de {@link RequestMetadata}. */
  public static Builder builder(String correlationId, String requestId) {
    return new Builder(correlationId, requestId);
  }

  /** Builder mutable; no es thread-safe. */
  public static final class Builder {
    private final String correlationId;
    private final String requestId;
    private @Nullable String channel;
    private @Nullable String clientId;
    private @Nullable String subject;
    private @Nullable String tenantId;
    private @Nullable String issuer;
    private Locale locale = Locale.ROOT;
    private Instant receivedAt = Instant.EPOCH;
    private Map<String, String> attributes = Map.of();

    private Builder(String correlationId, String requestId) {
      this.correlationId = correlationId;
      this.requestId = requestId;
    }

    public Builder channel(@Nullable String value) {
      this.channel = value;
      return this;
    }

    public Builder clientId(@Nullable String value) {
      this.clientId = value;
      return this;
    }

    public Builder subject(@Nullable String value) {
      this.subject = value;
      return this;
    }

    public Builder tenantId(@Nullable String value) {
      this.tenantId = value;
      return this;
    }

    public Builder issuer(@Nullable String value) {
      this.issuer = value;
      return this;
    }

    public Builder locale(Locale value) {
      this.locale = value;
      return this;
    }

    public Builder receivedAt(Instant value) {
      this.receivedAt = value;
      return this;
    }

    public Builder attributes(Map<String, String> value) {
      this.attributes = Map.copyOf(value);
      return this;
    }

    public RequestMetadata build() {
      return new RequestMetadata(
          correlationId,
          requestId,
          channel,
          clientId,
          subject,
          tenantId,
          issuer,
          locale,
          receivedAt,
          attributes);
    }
  }
}
