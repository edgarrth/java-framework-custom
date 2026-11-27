package pe.axiz.core.messaging;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import pe.axiz.core.context.RequestMetadata;
import pe.axiz.core.id.IdGenerator;

/**
 * Construye envelopes con el source del servicio, un prefijo de tipo y extensiones de contexto
 * (correlación, tenant y cabeceras de negocio propagadas).
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
   * Crea un envelope.
   *
   * @param eventId id a usar (el id del evento de dominio) o null para generar uno
   * @param type tipo de evento sin prefijo, por ejemplo {@code order-placed.v1}
   * @param subject id del agregado
   * @param payload objeto payload
   * @param metadata metadatos de la petición a propagar, o null
   * @return el envelope
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
    return EventEnvelope.builder()
        .id(eventId == null ? ids.newId() : eventId)
        .source(source)
        .type(typePrefix + type)
        .time(clock.instant())
        .subject(subject)
        .dataContentType(codec.contentType())
        .data(codec.encode(payload))
        .extensions(ext)
        .build();
  }
}
