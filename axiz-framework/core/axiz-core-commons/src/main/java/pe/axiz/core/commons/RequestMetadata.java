package pe.axiz.core.commons;

import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Immutable metadata of the request or message being processed.
 *
 * @param correlationId id shared by every call of the same business flow
 * @param requestId id of this particular request
 * @param channel origin channel (web, mobile, ...), free value declared by the service
 * @param clientId calling application, normally taken from the access token
 * @param subject end user, when there is one
 * @param tenantId tenant, only for multi-tenant services
 * @param issuer issuer of the access token that authenticated the caller, when there is one
 * @param locale preferred locale
 * @param receivedAt reception instant (UTC)
 * @param attributes business headers declared in the header catalog
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
   * Copy with the authenticated caller; the tenant is replaced only when the token carries one.
   *
   * @param callerClientId calling application
   * @param callerSubject end user, if any
   * @param callerTenant tenant from the token, if any
   * @return the enriched metadata
   */
  public RequestMetadata withCaller(
      @Nullable String callerClientId,
      @Nullable String callerSubject,
      @Nullable String callerTenant) {
    return withCaller(callerClientId, callerSubject, callerTenant, issuer);
  }

  /**
   * Copy with the authenticated caller and the issuer of its token.
   *
   * @param callerClientId calling application
   * @param callerSubject end user, if any
   * @param callerTenant tenant from the token, if any (otherwise the current one is kept)
   * @param callerIssuer token issuer, if any
   * @return the enriched metadata
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

  /** Builder for {@link RequestMetadata}. */
  public static Builder builder(String correlationId, String requestId) {
    return new Builder(correlationId, requestId);
  }

  /** Mutable builder; not thread-safe. */
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
      this.attributes = value;
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
