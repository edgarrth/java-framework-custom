package pe.axiz.core.sensitive;

/** Where a value is being written. Masking applies to all three outputs. */
public enum OutputChannel {
  RESPONSE,
  LOG,
  AUDIT
}
