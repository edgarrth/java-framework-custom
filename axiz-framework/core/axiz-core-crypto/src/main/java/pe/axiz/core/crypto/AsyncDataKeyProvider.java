package pe.axiz.core.crypto;

import java.util.concurrent.CompletionStage;

/**
 * Proveedor nativo de claves de envelope. Los resultados transfieren la propiedad (se cierra el
 * DataKey y se limpian los arrays de bytes en bruto). La cancelación debe llegar a la operación
 * remota; se prohíben los arrays de texto plano compartidos.
 */
public interface AsyncDataKeyProvider {
  CompletionStage<DataKeyProvider.DataKey> generate(String context);

  CompletionStage<byte[]> decrypt(byte[] encryptedKey, String context);
}
