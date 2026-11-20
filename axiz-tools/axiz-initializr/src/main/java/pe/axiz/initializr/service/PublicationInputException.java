package pe.axiz.initializr.service;

import java.util.Objects;

/** Closed set of safe input errors, detected before consuming delegated authorization. */
public final class PublicationInputException extends IllegalArgumentException {
  /** Request field needing correction; no generated content is included. */
  public enum Field {
    DESCRIPTION,
    FILES,
    CONTENT
  }

  private final Field field;

  public PublicationInputException(Field field) {
    super("Invalid GitHub publication input");
    this.field = Objects.requireNonNull(field);
  }

  public Field field() {
    return field;
  }
}
