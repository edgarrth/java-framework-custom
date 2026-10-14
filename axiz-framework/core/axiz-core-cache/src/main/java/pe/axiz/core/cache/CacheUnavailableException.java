package pe.axiz.core.cache;

/** The remote cache tier is not reachable. Callers degrade to the source of truth. */
public class CacheUnavailableException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public CacheUnavailableException(String message, Throwable cause) {
    super(message, cause);
  }
}
