package pe.axiz.extension.cqrs;

/**
 * Petición que recorre el pipeline.
 *
 * @param request el comando o la consulta
 * @param name nombre de la operación (clase de la petición en kebab-case), usado en spans, métricas
 *     y auditoría
 * @param resultType tipo de resultado declarado del handler
 * @param <R> tipo del resultado
 */
public record Invocation<R>(Request<R> request, String name, Class<?> resultType) {

  public boolean isCommand() {
    return request instanceof Command<?>;
  }
}
