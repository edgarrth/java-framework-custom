package pe.axiz.core.domain;

import java.util.UUID;
import pe.axiz.core.id.UuidV7;

/**
 * Typed identifier backed by a UUID, implemented by records:
 *
 * <pre>{@code
 * public record OrderId(UUID value) implements TypedId {
 *   public static OrderId newId() { return new OrderId(TypedId.newUuid()); }
 * }
 * }</pre>
 */
public interface TypedId extends ValueObject {

  UUID value();

  static UUID newUuid() {
    return UuidV7.generate();
  }

  default String asString() {
    return value().toString();
  }
}
