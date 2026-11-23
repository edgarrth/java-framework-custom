package pe.axiz.core.messaging;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Envelope CloudEvents 1.0. {@code data} es el payload codificado (JSON por defecto); las
 * extensiones llevan correlación, tracing, tenant y las cabeceras de negocio declaradas.
 *
 * <p>Inmutable: {@code data} se copia a la entrada y a la salida. Dos envelopes son iguales cuando
 * tienen la misma identidad CloudEvents ({@code source} e {@code id}).
 */
public final class EventEnvelope {

  public static final String SPEC_VERSION = "1.0";
  public static final String CORRELATION_ID = "correlationid";
  public static final String TRACEPARENT = "traceparent";
  public static final String TRACESTATE = "tracestate";

  /**
   * Contexto de traza (W3C {@code traceparent}) de la escritura en el outbox que almacenó el
   * evento. El relay publica el evento más tarde, fuera de la petición: continúa esta traza y
   * elimina la extensión, de modo que nunca llega al broker.
   */
  public static final String OUTBOX_TRACEPARENT = "outboxtraceparent";

  /** {@code tracestate} que acompaña a {@link #OUTBOX_TRACEPARENT}. */
  public static final String OUTBOX_TRACESTATE = "outboxtracestate";

  public static final String TENANT = "tenant";
  private static final Pattern EXTENSION_NAME = Pattern.compile("^[a-z0-9]{1,20}$");

  private final String id;
  private final String source;
  private final String type;
  private final Instant time;
  private final @Nullable String subject;
  private final String dataContentType;
  private final byte[] data;
  private final Map<String, String> extensions;

  /**
   * Crea un envelope.
   *
   * @param builder estado de construcción nominal
   */
  private EventEnvelope(Builder builder) {
    String id = Objects.requireNonNull(builder.id, "id");
    String source = Objects.requireNonNull(builder.source, "source");
    String type = Objects.requireNonNull(builder.type, "type");
    Instant time = Objects.requireNonNull(builder.time, "time");
    @Nullable String subject = builder.subject;
    String dataContentType = Objects.requireNonNull(builder.dataContentType, "dataContentType");
    byte[] data = Objects.requireNonNull(builder.data, "data");
    Map<String, String> extensions = Objects.requireNonNull(builder.extensions, "extensions");
    this.id = Objects.requireNonNull(id, "id");
    this.source = Objects.requireNonNull(source, "source");
    this.type = Objects.requireNonNull(type, "type");
    this.time = Objects.requireNonNull(time, "time");
    this.subject = subject;
    this.dataContentType = Objects.requireNonNull(dataContentType, "dataContentType");
    this.data = Objects.requireNonNull(data, "data").clone();
    for (String name : extensions.keySet()) {
      if (!EXTENSION_NAME.matcher(name).matches()) {
        throw new IllegalArgumentException("Invalid CloudEvents extension name: " + name);
      }
    }
    this.extensions = Map.copyOf(extensions);
  }

  public String id() {
    return id;
  }

  public String source() {
    return source;
  }

  public String type() {
    return type;
  }

  public Instant time() {
    return time;
  }

  public @Nullable String subject() {
    return subject;
  }

  public String dataContentType() {
    return dataContentType;
  }

  /** Extensiones CloudEvents, no modificables. */
  public Map<String, String> extensions() {
    return extensions;
  }

  /** Copia del payload codificado. */
  public byte[] data() {
    return data.clone();
  }

  public Optional<String> extension(String name) {
    return Optional.ofNullable(extensions.get(name.toLowerCase(Locale.ROOT)));
  }

  public Optional<String> correlationId() {
    return extension(CORRELATION_ID);
  }

  /** Copia con una extensión adicional. */
  public EventEnvelope withExtension(String name, String value) {
    Map<String, String> ext = new LinkedHashMap<>(extensions);
    ext.put(name.toLowerCase(Locale.ROOT), value);
    return EventEnvelope.builder()
        .id(id)
        .source(source)
        .type(type)
        .time(time)
        .subject(subject)
        .dataContentType(dataContentType)
        .data(data)
        .extensions(ext)
        .build();
  }

  /** Copia sin las extensiones indicadas. */
  public EventEnvelope withoutExtensions(String... names) {
    Map<String, String> ext = new LinkedHashMap<>(extensions);
    for (String n : names) {
      ext.remove(n.toLowerCase(Locale.ROOT));
    }
    return ext.size() == extensions.size()
        ? this
        : EventEnvelope.builder()
            .id(id)
            .source(source)
            .type(type)
            .time(time)
            .subject(subject)
            .dataContentType(dataContentType)
            .data(data)
            .extensions(ext)
            .build();
  }

  @Override
  public boolean equals(@Nullable Object o) {
    return o instanceof EventEnvelope e && id.equals(e.id) && source.equals(e.source);
  }

  @Override
  public int hashCode() {
    return Objects.hash(id, source);
  }

  @Override
  public String toString() {
    return "EventEnvelope[id="
        + id
        + ", type="
        + type
        + ", source="
        + source
        + ", subject="
        + subject
        + "]";
  }

  /** Construcción nominal; build verifica los valores obligatorios y las invariantes. */
  public static Builder builder() {
    return new Builder();
  }

  /**
   * Estado mutable de construcción; se usa un builder por operación, nunca como estado compartido.
   */
  public static final class Builder {
    private @Nullable String id = null;
    private @Nullable String source = null;
    private @Nullable String type = null;
    private @Nullable Instant time = null;
    private @Nullable String subject = null;
    private @Nullable String dataContentType = null;
    private byte @Nullable [] data = null;
    private @Nullable Map<String, String> extensions = Map.of();

    private Builder() {}

    public Builder id(String value) {
      this.id = value;
      return this;
    }

    public Builder source(String value) {
      this.source = value;
      return this;
    }

    public Builder type(String value) {
      this.type = value;
      return this;
    }

    public Builder time(Instant value) {
      this.time = value;
      return this;
    }

    public Builder subject(@Nullable String value) {
      this.subject = value;
      return this;
    }

    public Builder dataContentType(String value) {
      this.dataContentType = value;
      return this;
    }

    public Builder data(byte[] value) {
      this.data = value.clone();
      return this;
    }

    public Builder extensions(Map<String, String> value) {
      this.extensions = Map.copyOf(value);
      return this;
    }

    public EventEnvelope build() {
      return new EventEnvelope(this);
    }
  }
}
