package pe.axiz.spring.foundation;

import java.util.Objects;
import pe.axiz.core.identifiers.IdentifierRegistry;

/**
 * Registry used by {@link IdentifierConstraintValidator}. Validators are instantiated by the
 * validation provider, so the application registry (with custom types) is published here.
 */
public final class IdentifierValidation {

  private static volatile IdentifierRegistry registry = IdentifierRegistry.withDiscoveredTypes();

  private IdentifierValidation() {}

  static void use(IdentifierRegistry value) {
    registry = Objects.requireNonNull(value);
  }

  static IdentifierRegistry registry() {
    return registry;
  }
}
