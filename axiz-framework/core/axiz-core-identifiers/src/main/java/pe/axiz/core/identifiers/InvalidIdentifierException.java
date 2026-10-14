package pe.axiz.core.identifiers;

/** Thrown when a value is not a valid identifier of the requested type. */
public class InvalidIdentifierException extends IllegalArgumentException {

  private static final long serialVersionUID = 1L;

  private final String type;

  public InvalidIdentifierException(String type) {
    super("Invalid " + type);
    this.type = type;
  }

  public String type() {
    return type;
  }
}
