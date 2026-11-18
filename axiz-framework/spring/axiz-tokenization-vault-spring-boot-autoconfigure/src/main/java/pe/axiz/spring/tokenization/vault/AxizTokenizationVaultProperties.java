package pe.axiz.spring.tokenization.vault;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import pe.axiz.core.tokenization.TokenType;
import pe.axiz.core.tokenization.vault.TokenTypePolicy.PanHeuristicExemption;

/** Server-owned policy references. No keys, source values or request-controlled algorithms. */
@ConfigurationProperties("axiz.tokenization.vault")
public record AxizTokenizationVaultProperties(Auth auth, Map<String, TypePolicy> types) {
  public AxizTokenizationVaultProperties {
    Objects.requireNonNull(auth, "Explicit token vault identity policy is required");
    if (types == null || types.isEmpty() || types.size() > 64)
      throw new IllegalArgumentException("Explicit token vault type policies are required");
    types.keySet().forEach(TokenType::new);
    types = Map.copyOf(types);
  }

  /** Tenant comes from exactly one validated claim or an explicit single-tenant deployment. */
  public record Auth(
      @Nullable String tenantClaim,
      @Nullable String fixedTenant,
      String clientClaim,
      String domainsClaim,
      String authorityPrefix) {
    public Auth {
      if ((tenantClaim == null) == (fixedTenant == null))
        throw new IllegalArgumentException("Configure exactly one tenant source");
      if (tenantClaim != null) identifier(tenantClaim, 64);
      if (fixedTenant != null) identifier(fixedTenant, 128);
      identifier(clientClaim, 64);
      identifier(domainsClaim, 64);
      identifier(authorityPrefix, 64);
    }
  }

  /** A configured reference must match a registered profile's exact version. */
  public record TypePolicy(
      String profile,
      int version,
      String keyset,
      Set<String> domains,
      Set<String> purposes,
      Duration retention,
      Set<String> metadataKeys,
      @Nullable PanHeuristicExemption panException) {
    public TypePolicy {
      identifier(profile, 64);
      identifier(keyset, 64);
      if (version < 1
          || domains == null
          || domains.isEmpty()
          || domains.size() > 64
          || purposes == null
          || purposes.isEmpty()
          || purposes.size() > 32
          || retention == null
          || retention.isNegative()
          || retention.isZero()
          || retention.compareTo(Duration.ofDays(3650)) > 0)
        throw new IllegalArgumentException("Invalid token vault type policy");
      domains.forEach(value -> identifier(value, 64));
      purposes.forEach(value -> identifier(value, 64));
      domains = Set.copyOf(domains);
      purposes = Set.copyOf(purposes);
      metadataKeys = metadataKeys == null ? Set.of() : Set.copyOf(metadataKeys);
      if (metadataKeys.size() > 16) throw new IllegalArgumentException("Too many metadata keys");
      metadataKeys.forEach(value -> identifier(value, 32));
    }

    /** Named construction; required values and invariants are checked by build. */
    public static Builder builder() {
      return new Builder();
    }

    /** Mutable construction state; use one builder per operation, never as shared state. */
    public static final class Builder {
      private @Nullable String profile = null;
      private int version = 0;
      private @Nullable String keyset = null;
      private @Nullable Set<String> domains = Set.of();
      private @Nullable Set<String> purposes = Set.of();
      private @Nullable Duration retention = null;
      private @Nullable Set<String> metadataKeys = Set.of();
      private @Nullable PanHeuristicExemption panException = null;

      private Builder() {}

      public Builder profile(String value) {
        this.profile = value;
        return this;
      }

      public Builder version(int value) {
        this.version = value;
        return this;
      }

      public Builder keyset(String value) {
        this.keyset = value;
        return this;
      }

      public Builder domains(Set<String> value) {
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

      public TypePolicy build() {
        return new TypePolicy(
            Objects.requireNonNull(profile, "profile"),
            version,
            Objects.requireNonNull(keyset, "keyset"),
            Objects.requireNonNull(domains, "domains"),
            Objects.requireNonNull(purposes, "purposes"),
            Objects.requireNonNull(retention, "retention"),
            Objects.requireNonNull(metadataKeys, "metadataKeys"),
            panException);
      }
    }
  }

  static String identifier(@Nullable String value, int bound) {
    if (value == null || value.length() > bound || !value.matches("[A-Za-z0-9][A-Za-z0-9_.:-]*"))
      throw new IllegalArgumentException("Invalid token vault configuration identifier");
    return value;
  }
}
