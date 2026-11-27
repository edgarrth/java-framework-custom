package pe.axiz.core.secrets;

/** Un secreto obligatorio no está disponible en ninguna fuente. */
public class SecretNotFoundException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public SecretNotFoundException(String name) {
    super("Secret not found: " + name);
  }
}
