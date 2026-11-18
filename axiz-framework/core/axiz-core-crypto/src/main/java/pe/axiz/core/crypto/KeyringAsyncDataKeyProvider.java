package pe.axiz.core.crypto;

import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletionStage;
import pe.axiz.core.concurrent.Stages;
import pe.axiz.core.crypto.DataKeyProvider.DataKey;

/** Native asynchronous equivalent of the same bounded, context-authenticated routing format. */
public final class KeyringAsyncDataKeyProvider implements AsyncDataKeyProvider {
  private final Map<String, AsyncDataKeyProvider> providers;
  private final String writeKeyId;

  public KeyringAsyncDataKeyProvider(
      Map<String, AsyncDataKeyProvider> providers, String writeKeyId) {
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
  public CompletionStage<DataKey> generate(String context) {
    return Stages.consume(
        Stages.call(
            () ->
                Objects.requireNonNull(providers.get(writeKeyId))
                    .generate(DataKeyRouting.context(writeKeyId, context))),
        key -> DataKeyRouting.routed(key, writeKeyId),
        DataKey::close,
        DataKey::close);
  }

  @Override
  public CompletionStage<byte[]> decrypt(byte[] encryptedKey, String context) {
    return Stages.call(
        () -> {
          var route = DataKeyRouting.parse(encryptedKey);
          var provider = providers.get(route.keyId());
          if (provider == null) throw new CryptoException("Data-key route is not available");
          return Stages.consume(
              Stages.call(
                  () ->
                      provider.decrypt(
                          route.encrypted(), DataKeyRouting.context(route.keyId(), context))),
              byte[]::clone,
              bytes -> Arrays.fill(bytes, (byte) 0),
              bytes -> Arrays.fill(bytes, (byte) 0));
        });
  }
}
