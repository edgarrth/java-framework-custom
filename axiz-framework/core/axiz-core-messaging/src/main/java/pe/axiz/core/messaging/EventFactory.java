package pe.axiz.core.messaging;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import pe.axiz.core.commons.RequestMetadata;
import pe.axiz.core.id.IdGenerator;

/**
 * Builds envelopes with the service source, a type prefix and context extensions (correlation,
 * tenant and propagated business headers).
 */
public final class EventFactory {

  private final String source;
  private final String typePrefix;
  private final PayloadCodec codec;
  private final IdGenerator ids;
  private final Clock clock;

  public EventFactory(
      String source, String typePrefix, PayloadCodec codec, IdGenerator ids, Clock clock) {
    this.source = Objects.requireNonNull(source);
    this.typePrefix =
        typePrefix.endsWith(".") || typePrefix.isEmpty() ? typePrefix : typePrefix + ".";
    this.codec = Objects.requireNonNull(codec);
    this.ids = Objects.requireNonNull(ids);
    this.clock = Objects.requireNonNull(clock);
  }

  /**
   * Creates an envelope.
   *
   * @param eventId id to use (the domain event id) or null to generate one
   * @param type event type without prefix, e.g. {@code order-placed.v1}
   * @param subject aggregate id
   * @param payload payload object
   * @param metadata request metadata to propagate, or null
   * @return the envelope
   */
  public EventEnvelope create(
      @Nullable String eventId,
      String type,
      @Nullable String subject,
      Object payload,
      @Nullable RequestMetadata metadata) {
    Map<String, String> ext = new LinkedHashMap<>();
    if (metadata != null) {
      ext.put(EventEnvelope.CORRELATION_ID, metadata.correlationId());
      if (metadata.tenantId() != null) {
        ext.put(EventEnvelope.TENANT, metadata.tenantId());
      }
      if (metadata.channel() != null) {
        ext.put("channel", metadata.channel());
      }
    }
    return new EventEnvelope(
        eventId == null ? ids.newId() : eventId,
        source,
        typePrefix + type,
        clock.instant(),
        subject,
        codec.contentType(),
        codec.encode(payload),
        ext);
  }
}
