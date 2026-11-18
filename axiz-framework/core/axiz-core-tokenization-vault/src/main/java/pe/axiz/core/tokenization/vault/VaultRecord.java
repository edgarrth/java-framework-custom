package pe.axiz.core.tokenization.vault;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import pe.axiz.core.crypto.KeyedHash;
import pe.axiz.core.tokenization.Token;
import pe.axiz.core.tokenization.TokenErrors;
import pe.axiz.core.tokenization.TokenMetadata;

/** Stored encrypted association. Authenticity is established only by envelope decryption. */
public final class VaultRecord {
  private final Token token;
  private final VaultScope scope;
  private final String keysetId;
  private final List<KeyedHash> fingerprints;
  private final long revision;
  private final byte[] ciphertext;
  private final Instant createdAt;
  private final Instant expiresAt;
  private final Map<String, String> metadata;

  private VaultRecord(Builder builder) {
    Token token = Objects.requireNonNull(builder.token, "token");
    VaultScope scope = Objects.requireNonNull(builder.scope, "scope");
    String keysetId = Objects.requireNonNull(builder.keysetId, "keysetId");
    List<KeyedHash> fingerprints = Objects.requireNonNull(builder.fingerprints, "fingerprints");
    long revision = builder.revision;
    byte[] ciphertext = Objects.requireNonNull(builder.ciphertext, "ciphertext");
    Instant createdAt = Objects.requireNonNull(builder.createdAt, "createdAt");
    Instant expiresAt = Objects.requireNonNull(builder.expiresAt, "expiresAt");
    Map<String, String> metadata = Objects.requireNonNull(builder.metadata, "metadata");
    this.token = token;
    this.scope = scope;
    this.keysetId = VaultIdentifiers.identifier(keysetId, 64);

    if (revision < 1
        || ciphertext.length < 40
        || ciphertext.length > 100_000
        || !expiresAt.isAfter(createdAt)
        || fingerprints.isEmpty()
        || fingerprints.size() > 16) throw TokenErrors.failure(TokenErrors.INVALID);
    var keys = new HashSet<String>();
    for (KeyedHash fingerprint : fingerprints) {
      if (!keys.add(fingerprint.keyId())
          || !fingerprint.profileId().equals(scope.profileId())
          || fingerprint.canonicalVersion() != scope.canonicalVersion())
        throw TokenErrors.failure(TokenErrors.INVALID);
    }
    this.fingerprints = List.copyOf(fingerprints);
    this.revision = revision;
    this.ciphertext = ciphertext.clone();
    this.createdAt = createdAt;
    this.expiresAt = expiresAt;
    this.metadata = TokenMetadata.snapshot(metadata);
  }

  public Token token() {
    return token;
  }

  public VaultScope scope() {
    return scope;
  }

  public String keysetId() {
    return keysetId;
  }

  public List<KeyedHash> fingerprints() {
    return fingerprints;
  }

  public long revision() {
    return revision;
  }

  public byte[] ciphertext() {
    return ciphertext.clone();
  }

  public Instant createdAt() {
    return createdAt;
  }

  public Instant expiresAt() {
    return expiresAt;
  }

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP",
      justification =
          "TokenMetadata.snapshot returns Map.copyOf with immutable String keys and values.")
  public Map<String, String> metadata() {
    return metadata;
  }

  @Override
  public String toString() {
    return "VaultRecord[" + token.masked() + ", revision=" + revision + "]";
  }

  /** Named construction; required values and invariants are checked by build. */
  public static Builder builder() {
    return new Builder();
  }

  /** Mutable construction state; use one builder per operation, never as shared state. */
  public static final class Builder {
    private @Nullable Token token = null;
    private @Nullable VaultScope scope = null;
    private @Nullable String keysetId = null;
    private @Nullable List<KeyedHash> fingerprints = List.of();
    private long revision = 0;
    private byte @Nullable [] ciphertext = null;
    private @Nullable Instant createdAt = null;
    private @Nullable Instant expiresAt = null;
    private @Nullable Map<String, String> metadata = Map.of();

    private Builder() {}

    public Builder token(Token value) {
      this.token = value;
      return this;
    }

    public Builder scope(VaultScope value) {
      this.scope = value;
      return this;
    }

    public Builder keysetId(String value) {
      this.keysetId = value;
      return this;
    }

    public Builder fingerprints(List<KeyedHash> value) {
      this.fingerprints = List.copyOf(value);
      return this;
    }

    public Builder revision(long value) {
      this.revision = value;
      return this;
    }

    public Builder ciphertext(byte[] value) {
      this.ciphertext = value.clone();
      return this;
    }

    public Builder createdAt(Instant value) {
      this.createdAt = value;
      return this;
    }

    public Builder expiresAt(Instant value) {
      this.expiresAt = value;
      return this;
    }

    public Builder metadata(Map<String, String> value) {
      this.metadata = Map.copyOf(value);
      return this;
    }

    public VaultRecord build() {
      return new VaultRecord(this);
    }
  }
}
