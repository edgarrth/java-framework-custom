package pe.axiz.spring.audit;

import java.util.Collections;
import java.util.IdentityHashMap;
import org.jspecify.annotations.Nullable;
import pe.axiz.core.audit.AsyncAuditPublisherDecorator;
import pe.axiz.core.audit.AuditPublisherDecorator;

/**
 * Recorrido acotado por identidad, incluidos los decoradores del usuario; ningún ciclo recursivo
 * puede ocultar una cola.
 */
final class AuditPublisherLookup {
  private AuditPublisherLookup() {}

  static <T> @Nullable T find(@Nullable Object publisher, Class<T> expected) {
    var visited = Collections.newSetFromMap(new IdentityHashMap<Object, Boolean>());
    Object current = publisher;
    while (current != null && visited.size() < 64 && visited.add(current)) {
      if (expected.isInstance(current)) return expected.cast(current);
      if (current instanceof AuditPublisherDecorator decorator) current = decorator.delegate();
      else if (current instanceof AsyncAuditPublisherDecorator decorator)
        current = decorator.delegate();
      else return null;
    }
    return null;
  }
}
