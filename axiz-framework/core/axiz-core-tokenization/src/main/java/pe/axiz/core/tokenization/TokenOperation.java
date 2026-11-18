package pe.axiz.core.tokenization;

/** Each operation requires its own permission on the server. */
public enum TokenOperation {
  TOKENIZE,
  LOOKUP,
  DESCRIBE,
  DETOKENIZE,
  DELETE
}
