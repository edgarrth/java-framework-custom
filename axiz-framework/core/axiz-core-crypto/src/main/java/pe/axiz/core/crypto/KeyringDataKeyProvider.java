package pe.axiz.core.crypto;

import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Immutable bounded wrapping-key routing. Providers and clients are borrowed, not closed here. A
 * route is an immutable identity: never repoint it at another key, alias or wrapping algorithm.
 * Underlying providers must authenticate the supplied context. There is no trial decryption.
 */
public final class KeyringDataKeyProvider implements DataKeyProvider {
  private final Map<String, DataKeyProvider> providers;
  private final String writeKeyId;

  public KeyringDataKeyProvider(Map<String, DataKeyProvider> providers, String writeKeyId) {
    this.providers = DataKeyRouting.providers(providers, writeKeyId);
    this.writeKeyId = writeKeyId;
  }

  public String writeKeyId() {
    return writeKeyId;
  }

  public Set<String> readKeyIds() {
    return providers.keySet();
  }

  @Override
  public DataKey generate(String context) {
    try (DataKey key =
        Objects.requireNonNull(providers.get(writeKeyId))
            .generate(DataKeyRouting.context(writeKeyId, context))) {
      return DataKeyRouting.routed(key, writeKeyId);
    }
  }

  @Override
  public byte[] decrypt(byte[] encryptedKey, String context) {
    var route = DataKeyRouting.parse(encryptedKey);
    var provider = providers.get(route.keyId());
    if (provider == null) throw new CryptoException("Data-key route is not available");
    return provider.decrypt(route.encrypted(), DataKeyRouting.context(route.keyId(), context));
  }

  /**
   * Unauthenticated header inspection for maintenance planning; decrypt to establish authenticity.
   */
  public static String envelopeKeyId(byte[] envelope) {
    return DataKeyRouting.parse(EnvelopePayload.parse(envelope).encryptedKey()).keyId();
  }
}
