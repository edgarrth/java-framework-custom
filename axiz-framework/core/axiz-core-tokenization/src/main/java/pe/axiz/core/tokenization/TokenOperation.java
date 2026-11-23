package pe.axiz.core.tokenization;

/** Cada operación requiere su propio permiso en el servidor. */
public enum TokenOperation {
  TOKENIZE,
  LOOKUP,
  DESCRIBE,
  DETOKENIZE,
  DELETE
}
