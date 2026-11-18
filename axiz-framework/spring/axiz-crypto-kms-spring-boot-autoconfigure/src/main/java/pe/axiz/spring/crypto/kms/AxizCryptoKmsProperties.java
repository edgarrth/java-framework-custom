package pe.axiz.spring.crypto.kms;

import java.time.Duration;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * KMS envelope encryption.
 *
 * @param keyId KMS key id, ARN or alias
 * @param dataKeyMaxAge how long a data key is reused for encryption
 * @param dataKeyMaxUses how many encryptions a data key serves
 * @param keyRing optional immutable route identifiers to immutable KMS key ARNs for dual reads
 */
@ConfigurationProperties("axiz.crypto.kms")
public record AxizCryptoKmsProperties(
    @Nullable String keyId,
    @DefaultValue("5m") Duration dataKeyMaxAge,
    @DefaultValue("1000") long dataKeyMaxUses,
    Map<String, String> keyRing) {
  public AxizCryptoKmsProperties {
    if (keyId != null && (keyId.isBlank() || keyId.contains("${")))
      throw new IllegalArgumentException("axiz.crypto.kms.key-id must not be blank or unresolved");
    keyRing = keyRing == null ? Map.of() : Map.copyOf(keyRing);
    if (!keyRing.isEmpty()) {
      if (keyId == null
          || keyRing.size() > 16
          || !keyRing.containsValue(keyId)
          || new HashSet<>(keyRing.values()).size() != keyRing.size())
        throw new IllegalArgumentException("KMS key ring must include its unique write key");
      keyRing.forEach(
          (route, arn) -> {
            if (!route.matches("[A-Za-z0-9_-]{1,32}")
                || !arn.matches(
                    "arn:aws(?:-[a-z]+)*:kms:[a-z0-9-]+:[0-9]{12}:key/"
                        + "(?:[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}|mrk-[0-9a-f]{32})"))
              throw new IllegalArgumentException(
                  "KMS key ring requires immutable key ARNs and bounded route identifiers");
          });
    }
    if (!dataKeyMaxAge.isPositive() || dataKeyMaxUses < 1)
      throw new IllegalArgumentException("KMS data key cache policy must be positive");
  }

  String writeRoute() {
    String write = Objects.requireNonNull(keyId);
    return keyRing.entrySet().stream()
        .filter(entry -> entry.getValue().equals(write))
        .map(Map.Entry::getKey)
        .findFirst()
        .orElseThrow();
  }
}
