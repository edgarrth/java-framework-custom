package pe.axiz.core.tokenization.vault;

import java.time.Duration;
import java.util.Objects;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import pe.axiz.core.tokenization.TokenDomain;
import pe.axiz.core.tokenization.TokenErrors;
import pe.axiz.core.tokenization.TokenProfile;
import pe.axiz.core.tokenization.TokenType;

/** Política de tipos versionada propiedad del servidor; nunca proviene de campos de la petición. */
public record TokenTypePolicy(
    TokenType type,
    TokenProfile profile,
    String keysetId,
    Set<TokenDomain> domains,
    Set<String> purposes,
    Duration retention,
    Set<String> metadataKeys,
    @Nullable PanHeuristicExemption panException) {
  public TokenTypePolicy {
    Objects.requireNonNull(type);
    Objects.requireNonNull(profile);
    Objects.requireNonNull(retention);
    VaultIdentifiers.identifier(profile.id(), 64);
    VaultIdentifiers.identifier(keysetId, 64);
    if (profile.version() < 1
        || domains.isEmpty()
        || domains.size() > 64
        || purposes.isEmpty()
        || purposes.size() > 32
        || retention.isNegative()
        || retention.isZero()
        || retention.compareTo(Duration.ofDays(3650)) > 0
        || metadataKeys.size() > 16
        || Set.of(
                "CVV",
                "CVV2",
                "CVC",
                "CVC2",
                "CID",
                "PIN",
                "PINBLOCK",
                "TRACK1",
                "TRACK2",
                "TRACKDATA")
            .contains(type.value())) throw TokenErrors.failure(TokenErrors.INVALID);
    purposes.forEach(purpose -> VaultIdentifiers.identifier(purpose, 64));
    metadataKeys.forEach(key -> VaultIdentifiers.identifier(key, 32));
    domains = Set.copyOf(domains);
    purposes = Set.copyOf(purposes);
    metadataKeys = Set.copyOf(metadataKeys);
    if (profile.cardData() && panException != null) throw TokenErrors.failure(TokenErrors.INVALID);
  }

  /**
   * Excepción revisada y ligada a una versión para identificadores legítimos que coinciden con la
   * heurística de PAN.
   */
  public record PanHeuristicExemption(String reason, String reviewReference, int profileVersion) {
    public PanHeuristicExemption {
      VaultIdentifiers.identifier(reason, 128);
      VaultIdentifiers.identifier(reviewReference, 128);
      if (profileVersion < 1) throw TokenErrors.failure(TokenErrors.INVALID);
    }
  }

  /** Construcción nominal; build verifica los valores obligatorios y las invariantes. */
  public static Builder builder() {
    return new Builder();
  }

  /**
   * Estado mutable de construcción; se usa un builder por operación, nunca como estado compartido.
   */
  public static final class Builder {
    private @Nullable TokenType type = null;
    private @Nullable TokenProfile profile = null;
    private @Nullable String keysetId = null;
    private @Nullable Set<TokenDomain> domains = Set.of();
    private @Nullable Set<String> purposes = Set.of();
    private @Nullable Duration retention = null;
    private @Nullable Set<String> metadataKeys = Set.of();
    private @Nullable PanHeuristicExemption panException = null;

    private Builder() {}

    public Builder type(TokenType value) {
      this.type = value;
      return this;
    }

    public Builder profile(TokenProfile value) {
      this.profile = value;
      return this;
    }

    public Builder keysetId(String value) {
      this.keysetId = value;
      return this;
    }

    public Builder domains(Set<TokenDomain> value) {
      this.domains = Set.copyOf(value);
      return this;
    }

    public Builder purposes(Set<String> value) {
      this.purposes = Set.copyOf(value);
      return this;
    }

    public Builder retention(Duration value) {
      this.retention = value;
      return this;
    }

    public Builder metadataKeys(Set<String> value) {
      this.metadataKeys = Set.copyOf(value);
      return this;
    }

    public Builder panException(@Nullable PanHeuristicExemption value) {
      this.panException = value;
      return this;
    }

    public TokenTypePolicy build() {
      return new TokenTypePolicy(
          Objects.requireNonNull(type, "type"),
          Objects.requireNonNull(profile, "profile"),
          Objects.requireNonNull(keysetId, "keysetId"),
          Objects.requireNonNull(domains, "domains"),
          Objects.requireNonNull(purposes, "purposes"),
          Objects.requireNonNull(retention, "retention"),
          Objects.requireNonNull(metadataKeys, "metadataKeys"),
          panException);
    }
  }
}
