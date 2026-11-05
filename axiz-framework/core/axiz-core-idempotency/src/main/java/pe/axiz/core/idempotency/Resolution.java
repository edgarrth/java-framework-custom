package pe.axiz.core.idempotency;

import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** Authoritative reconciliation supplied by the application, never inferred from an HTTP error. */
public record Resolution(@Nullable StoredResponse response) {
  public static Resolution completed(StoredResponse response) {
    return new Resolution(Objects.requireNonNull(response));
  }

  public static Resolution withoutEffect() {
    return new Resolution(null);
  }
}
