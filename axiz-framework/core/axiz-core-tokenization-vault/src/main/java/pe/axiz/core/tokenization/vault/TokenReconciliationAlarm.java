package pe.axiz.core.tokenization.vault;

/**
 * Alerta técnica cuando el registro durable de incertidumbre también falla; no acepta excepciones
 * ni valores.
 */
@FunctionalInterface
public interface TokenReconciliationAlarm {
  void failed(UncertainTokenOperation operation);
}
