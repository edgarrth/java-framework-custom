package pe.axiz.core.secrets;

/** A required secret is not available in any source. */
public class SecretNotFoundException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public SecretNotFoundException(String name) {
    super("Secret not found: " + name);
  }
}
