package pe.axiz.framework.cqrs;

/** No handler is registered for a request type: a configuration error, not a client error. */
public class HandlerNotFoundException extends IllegalStateException {

  private static final long serialVersionUID = 1L;

  public HandlerNotFoundException(Class<?> requestType) {
    super("No handler registered for " + requestType.getName());
  }
}
