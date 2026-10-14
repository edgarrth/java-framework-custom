package pe.axiz.core.audit;

/** An audit event could not be delivered to its destination. */
public class AuditPublishException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public AuditPublishException(String message, Throwable cause) {
    super(message, cause);
  }
}
