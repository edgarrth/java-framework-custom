package pe.axiz.core.tokenization.vault;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** Closed maintenance evidence. No source value, ciphertext, HMAC or opaque scan cursor. */
public record VaultMaintenanceEvent(
    Action action,
    TokenCaller caller,
    String keysetId,
    long keyVersion,
    @Nullable VaultScope scope,
    String tokenReference,
    Result result,
    UUID operationId,
    Instant at) {
  /** Separate administrative operations; not additional permissions on the consumer API. */
  public enum Action {
    INITIALIZE,
    ACTIVATE,
    REINDEX,
    REENCRYPT,
    INITIALIZE_WRAPPING,
    ACTIVATE_WRAPPING,
    RETIRE_WRAPPING,
    PRUNE,
    RETIRE,
    PURGE
  }

  /** Whether a privileged attempt was confirmed, denied or left uncertain. */
  public enum Result {
    STARTED,
    SUCCESS,
    DENIED,
    CONFLICT,
    INTEGRITY_FAILURE,
    UNCERTAIN,
    UNAVAILABLE
  }

  public VaultMaintenanceEvent {
    Objects.requireNonNull(action);
    Objects.requireNonNull(caller);
    Objects.requireNonNull(result);
    Objects.requireNonNull(operationId);
    Objects.requireNonNull(at);
    VaultIdentifiers.identifier(keysetId, 64);
    if (keyVersion < 1
        || (!tokenReference.equals("none") && !tokenReference.matches("tok_\\*{18}[0-9A-Za-z]{4}")))
      throw new IllegalArgumentException("Invalid maintenance evidence");
  }

  @Override
  public String toString() {
    return "VaultMaintenanceEvent[" + action + ", " + result + "]";
  }

  /** Named construction; required values and invariants are checked by build. */
  public static Builder builder() {
    return new Builder();
  }

  /** Mutable construction state; use one builder per operation, never as shared state. */
  public static final class Builder {
    private @Nullable Action action = null;
    private @Nullable TokenCaller caller = null;
    private @Nullable String keysetId = null;
    private long keyVersion = 0;
    private @Nullable VaultScope scope = null;
    private @Nullable String tokenReference = null;
    private @Nullable Result result = null;
    private @Nullable UUID operationId = null;
    private @Nullable Instant at = null;

    private Builder() {}

    public Builder action(Action value) {
      this.action = value;
      return this;
    }

    public Builder caller(TokenCaller value) {
      this.caller = value;
      return this;
    }

    public Builder keysetId(String value) {
      this.keysetId = value;
      return this;
    }

    public Builder keyVersion(long value) {
      this.keyVersion = value;
      return this;
    }

    public Builder scope(@Nullable VaultScope value) {
      this.scope = value;
      return this;
    }

    public Builder tokenReference(String value) {
      this.tokenReference = value;
      return this;
    }

    public Builder result(Result value) {
      this.result = value;
      return this;
    }

    public Builder operationId(UUID value) {
      this.operationId = value;
      return this;
    }

    public Builder at(Instant value) {
      this.at = value;
      return this;
    }

    public VaultMaintenanceEvent build() {
      return new VaultMaintenanceEvent(
          Objects.requireNonNull(action, "action"),
          Objects.requireNonNull(caller, "caller"),
          Objects.requireNonNull(keysetId, "keysetId"),
          keyVersion,
          scope,
          Objects.requireNonNull(tokenReference, "tokenReference"),
          Objects.requireNonNull(result, "result"),
          Objects.requireNonNull(operationId, "operationId"),
          Objects.requireNonNull(at, "at"));
    }
  }
}
