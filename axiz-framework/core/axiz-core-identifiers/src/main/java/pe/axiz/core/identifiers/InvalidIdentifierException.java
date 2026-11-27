package pe.axiz.core.identifiers;

/** Se lanza cuando un valor no es un identificador válido del tipo solicitado. */
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
