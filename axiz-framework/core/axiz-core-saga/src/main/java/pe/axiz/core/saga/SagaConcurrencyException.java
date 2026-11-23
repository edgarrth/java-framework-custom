package pe.axiz.core.saga;

/** Otra instancia actualizó la saga de forma concurrente. */
public class SagaConcurrencyException extends IllegalStateException {

  private static final long serialVersionUID = 1L;

  public SagaConcurrencyException(String sagaId) {
    super("Saga " + sagaId + " was updated concurrently");
  }
}
