package pe.axiz.core.tokenization.vault;

import org.jspecify.annotations.Nullable;

/**
 * Puerto de job de mantenimiento síncrono y privilegiado. Nunca se expone como endpoint público de
 * tokenización ni se implementa envolviendo este puerto bloqueante en un executor reactivo. El
 * servidor operativo usa una identidad distinta que no puede escribir ítems de control ni recorrer
 * esta tabla.
 */
public interface TokenVaultMaintenance extends TokenVault {
  /**
   * Control independiente y fuertemente consistente de las claves de envoltura; sin recurrir a las
   * claves de huella.
   */
  WrappingKeyState wrappingState(String keysetId);

  /**
   * Inicializa un control de envoltura una sola vez, con la versión 1 y su única ruta de escritura.
   */
  boolean initializeWrapping(WrappingKeyState initial);

  /** Transición condicional tras el protocolo gobernado de rotación de claves de envoltura. */
  boolean transitionWrapping(WrappingKeyTransition transition);

  /**
   * El estado inicial tiene la versión 1 y solo su clave de escritura. Nunca sobrescribe un control
   * existente.
   */
  boolean initialize(FingerprintKeyState initial);

  /**
   * Recorrido consistente y acotado de los registros de un keyset; las páginas vacías aún pueden
   * tener un cursor.
   */
  VaultScanPage scan(String keysetId, @Nullable String cursor, int limit);

  /**
   * Cuenta los ítems de referencia antiguos, incluidos los huérfanos, con la misma semántica de
   * recorrido acotado.
   */
  VaultReferencePage references(String keysetId, String keyId, @Nullable String cursor, int limit);

  /**
   * CAS sobre todo el estado de control esperado; lo emite solo el protocolo de rotación gobernado.
   */
  boolean transition(VaultKeyTransition transition);

  /**
   * Reemplaza de forma atómica un registro sin cambios y sus referencias propias bajo la barrera de
   * control. Solo puede agregar la huella de escritura actual; no puede resucitar una huella de
   * lectura retirada. Eliminar una huella antigua requiere conservar la huella de escritura actual.
   */
  MigrationOutcome replace(
      VaultRecord expected, VaultRecord replacement, FingerprintKeyState state);
}
