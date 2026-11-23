package pe.axiz.core.audit;

/** No se pudo entregar un evento de auditoría a su destino. */
public class AuditPublishException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public AuditPublishException(String message, Throwable cause) {
    super(message, cause);
  }
}
