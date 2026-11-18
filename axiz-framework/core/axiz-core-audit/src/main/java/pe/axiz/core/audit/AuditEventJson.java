package pe.axiz.core.audit;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import pe.axiz.core.commons.TransactionOutcome;
import pe.axiz.core.json.JsonReader;
import pe.axiz.core.json.JsonWriter;

/** The audit wire codec preserves the canonical numeric representation needed for verification. */
public final class AuditEventJson {
  private static final Set<String> EVENT_FIELDS =
      Set.of(
          "id",
          "occurredAt",
          "service",
          "action",
          "resourceType",
          "resourceId",
          "actor",
          "outcome",
          "transactionOutcome",
          "operationOutcome",
          "errorCode",
          "correlationId",
          "channel",
          "tenant",
          "details",
          "detailsSanitized",
          "persistence",
          "integrity");

  private AuditEventJson() {}

  public static String write(AuditEvent event) {
    return JsonWriter.writeCanonical(event.toMap());
  }

  /** Rejects unknown fields, malformed or noncanonical numeric values and lossy sanitization. */
  public static AuditEvent read(String json) {
    try {
      Map<String, @Nullable Object> source = object(JsonReader.parseCanonical(json));
      known(source, EVENT_FIELDS);
      Map<String, @Nullable Object> actor = object(source.get("actor"));
      known(actor, Set.of("subject", "clientId", "ip"));
      var event =
          AuditEvent.builder()
              .id(text(source, "id"))
              .occurredAt(Instant.parse(text(source, "occurredAt")))
              .service(text(source, "service"))
              .action(text(source, "action"))
              .resourceType(text(source, "resourceType"))
              .resourceId(optionalText(source, "resourceId"))
              .actor(
                  new AuditEvent.Actor(
                      optionalText(actor, "subject"),
                      optionalText(actor, "clientId"),
                      optionalText(actor, "ip")))
              .outcome(AuditEvent.Outcome.valueOf(text(source, "outcome")))
              .errorCode(optionalText(source, "errorCode"))
              .correlationId(optionalText(source, "correlationId"))
              .channel(optionalText(source, "channel"))
              .tenant(optionalText(source, "tenant"))
              .details(object(source.get("details")))
              .transactionOutcome(TransactionOutcome.valueOf(text(source, "transactionOutcome")))
              .operationOutcome(TransactionOutcome.valueOf(text(source, "operationOutcome")))
              .persistence(optionalText(source, "persistence"))
              .integrity(
                  source.get("integrity") == null
                      ? null
                      : integrity(object(source.get("integrity"))))
              .detailsSanitized(Math.toIntExact(integer(source, "detailsSanitized")))
              .build();
      if (!write(event).equals(JsonWriter.writeCanonical(source)))
        throw new IllegalArgumentException("Audit JSON must not lose data during decoding");
      return event;
    } catch (RuntimeException malformed) {
      // Parser and date conversion exceptions may embed an untrusted field value.
      throw new IllegalArgumentException("Invalid audit JSON");
    }
  }

  private static AuditIntegrity integrity(Map<String, @Nullable Object> value) {
    known(value, Set.of("chainId", "sequence", "previousHash", "hash", "keyId", "previousAttempt"));
    AttemptRef previous = null;
    if (value.get("previousAttempt") != null) {
      var attempt = object(value.get("previousAttempt"));
      known(attempt, Set.of("chainId", "sequence", "hash", "outcome"));
      previous =
          new AttemptRef(
              text(attempt, "chainId"),
              integer(attempt, "sequence"),
              text(attempt, "hash"),
              AttemptOutcome.valueOf(text(attempt, "outcome")));
    }
    return new AuditIntegrity(
        text(value, "chainId"),
        integer(value, "sequence"),
        text(value, "previousHash"),
        text(value, "hash"),
        optionalText(value, "keyId"),
        previous);
  }

  @SuppressWarnings("unchecked")
  private static Map<String, @Nullable Object> object(@Nullable Object value) {
    if (!(value instanceof Map<?, ?> map)
        || map.keySet().stream().anyMatch(key -> !(key instanceof String)))
      throw new IllegalArgumentException("Expected audit object");
    return (Map<String, @Nullable Object>) map;
  }

  private static void known(Map<String, @Nullable Object> value, Set<String> fields) {
    if (!fields.containsAll(value.keySet()))
      throw new IllegalArgumentException("Unknown audit field");
  }

  private static String text(Map<String, @Nullable Object> value, String key) {
    if (!(value.get(key) instanceof String text))
      throw new IllegalArgumentException("Expected audit text");
    return text;
  }

  private static @Nullable String optionalText(Map<String, @Nullable Object> value, String key) {
    return value.get(key) == null ? null : text(value, key);
  }

  private static long integer(Map<String, @Nullable Object> value, String key) {
    if (!(value.get(key) instanceof Number number))
      throw new IllegalArgumentException("Expected audit integer");
    return new BigDecimal(number.toString()).longValueExact();
  }
}
