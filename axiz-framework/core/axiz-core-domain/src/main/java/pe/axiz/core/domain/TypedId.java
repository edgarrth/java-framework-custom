package pe.axiz.core.domain;

import java.util.UUID;
import pe.axiz.core.id.UuidV7;

/**
 * Identificador tipado respaldado por un UUID e implementado por records. Ejemplo:
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
