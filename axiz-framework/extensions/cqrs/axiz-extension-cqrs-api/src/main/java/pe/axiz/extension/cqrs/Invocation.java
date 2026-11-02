package pe.axiz.extension.cqrs;

/**
 * One request travelling through the pipeline.
 *
 * @param request the command or query
 * @param name operation name (request class in kebab-case), used in spans, metrics and audit
 * @param resultType declared result type of the handler
 * @param <R> result type
 */
public record Invocation<R>(Request<R> request, String name, Class<?> resultType) {

  public boolean isCommand() {
    return request instanceof Command<?>;
  }
}
