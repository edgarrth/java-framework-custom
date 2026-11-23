package pe.axiz.core.tenancy;

/** La operación necesita un tenant pero no hay ninguno ligado. */
public class TenantRequiredException extends IllegalStateException {

  private static final long serialVersionUID = 1L;

  public TenantRequiredException() {
    super("No tenant bound to the current context");
  }
}
