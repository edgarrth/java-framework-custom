package pe.axiz.core.cache;

/** El nivel de caché remoto no es accesible. Quienes llaman degradan a la fuente de verdad. */
public class CacheUnavailableException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public CacheUnavailableException(String message, Throwable cause) {
    super(message, cause);
  }
}
