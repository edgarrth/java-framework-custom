package pe.axiz.core.tokenization.vault;

/** Technical alert when the durable uncertainty log also fails; accepts no exception or value. */
@FunctionalInterface
public interface TokenReconciliationAlarm {
  void failed(UncertainTokenOperation operation);
}
