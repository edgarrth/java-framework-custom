package pe.axiz.core.crypto;

import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Enrutamiento inmutable y acotado de claves de envoltura. Los proveedores y clientes son prestados
 * y no se cierran aquí. Una ruta es una identidad inmutable: nunca se redirige a otra clave, alias
 * o algoritmo de envoltura. Los proveedores subyacentes deben autenticar el contexto suministrado.
 * No hay descifrado por tanteo.
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
   * Inspección no autenticada de la cabecera para planificar el mantenimiento; se descifra para
   * establecer la autenticidad.
   */
  public static String envelopeKeyId(byte[] envelope) {
    return DataKeyRouting.parse(EnvelopePayload.parse(envelope).encryptedKey()).keyId();
  }
}
