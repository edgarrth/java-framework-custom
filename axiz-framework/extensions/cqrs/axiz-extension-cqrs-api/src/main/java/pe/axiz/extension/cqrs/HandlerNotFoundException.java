package pe.axiz.extension.cqrs;

/**
 * No hay handler registrado para un tipo de petición: es un error de configuración, no un error del
 * cliente.
 */
public class HandlerNotFoundException extends IllegalStateException {

  private static final long serialVersionUID = 1L;

  public HandlerNotFoundException(Class<?> requestType) {
    super("No handler registered for " + requestType.getName());
  }
}
