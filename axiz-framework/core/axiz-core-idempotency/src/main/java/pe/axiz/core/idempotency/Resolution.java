package pe.axiz.core.idempotency;

import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** Reconciliación autoritativa suministrada por la aplicación, nunca inferida de un error HTTP. */
public record Resolution(@Nullable StoredResponse response) {
  public static Resolution completed(StoredResponse response) {
    return new Resolution(Objects.requireNonNull(response));
  }

  public static Resolution withoutEffect() {
    return new Resolution(null);
  }
}
