package pe.axiz.spring.crypto.kms;

import java.time.Duration;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Cifrado de sobre con KMS.
 *
 * @param keyId id, ARN o alias de la clave KMS
 * @param dataKeyMaxAge tiempo durante el cual se reutiliza una clave de datos para cifrar
 * @param dataKeyMaxUses número de cifrados que atiende una clave de datos
 * @param keyRing identificadores de ruta inmutables opcionales hacia ARN inmutables de claves KMS
 *     para lecturas duales
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
            if (!route.matches("[A-Za-z0-9_-]{1,32}") || !isImmutableKmsKeyArn(arn))
              throw new IllegalArgumentException(
                  "KMS key ring requires immutable key ARNs and bounded route identifiers");
          });
    }
    if (!dataKeyMaxAge.isPositive() || dataKeyMaxUses < 1)
      throw new IllegalArgumentException("KMS data key cache policy must be positive");
  }

  private static boolean isImmutableKmsKeyArn(String arn) {
    String[] fields = arn.split(":", 6);
    if (fields.length != 6
        || !fields[0].equals("arn")
        || !fields[2].equals("kms")
        || !fields[3].matches("[a-z0-9-]+")
        || !fields[4].matches("[0-9]{12}")
        || !fields[5].matches(
            "key/(?:[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}|mrk-[0-9a-f]{32})")) {
      return false;
    }
    // Se validan los segmentos de partición por separado, sin repetición anidada en la expresión
    // regular.
    String[] partition = fields[1].split("-", -1);
    if (!partition[0].equals("aws")) return false;
    for (int i = 1; i < partition.length; i++) {
      if (!partition[i].matches("[a-z]+")) return false;
    }
    return true;
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
