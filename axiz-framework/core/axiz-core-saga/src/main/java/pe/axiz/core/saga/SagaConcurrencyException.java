package pe.axiz.core.saga;

/** Another instance updated the saga concurrently. */
public class SagaConcurrencyException extends IllegalStateException {

  private static final long serialVersionUID = 1L;

  public SagaConcurrencyException(String sagaId) {
    super("Saga " + sagaId + " was updated concurrently");
  }
}
