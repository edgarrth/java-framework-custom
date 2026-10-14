package pe.axiz.core.tenancy;

/** The operation needs a tenant but none is bound. */
public class TenantRequiredException extends IllegalStateException {

  private static final long serialVersionUID = 1L;

  public TenantRequiredException() {
    super("No tenant bound to the current context");
  }
}
