package pe.axiz.extension.cqrs;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * Buses de comandos y consultas en Java simple. Los handlers se registran con su tipo de petición
 * (que el starter de Spring resuelve a partir de los genéricos); los duplicados fallan en la
 * construcción y las peticiones desconocidas fallan con {@link HandlerNotFoundException}.
 */
public final class Buses implements CommandBus, QueryBus {

  /**
   * Handler y los tipos que atiende.
   *
   * @param requestType clase de la petición
   * @param resultType clase del resultado
   * @param handler función del handler
   */
  public record Registration(
      Class<?> requestType, Class<?> resultType, Function<Object, Object> handler) {}

  private final Map<Class<?>, Registration> handlers;
  private final List<PipelineBehavior> behaviors;

  public Buses(List<Registration> registrations, List<PipelineBehavior> behaviors) {
    Map<Class<?>, Registration> map = new LinkedHashMap<>();
    for (Registration r : registrations) {
      if (map.put(r.requestType(), r) != null) {
        throw new IllegalStateException("Two handlers for " + r.requestType().getName());
      }
    }
    this.handlers = Map.copyOf(map);
    this.behaviors =
        behaviors.stream().sorted(Comparator.comparingInt(PipelineBehavior::order)).toList();
  }

  /** Utilidad de registro tipado para comandos. */
  @SuppressWarnings("unchecked")
  public static <C extends Command<R>, R> Registration command(
      Class<C> type, Class<R> result, CommandHandler<C, R> handler) {
    return new Registration(type, result, req -> handler.handle((C) req));
  }

  /** Utilidad de registro tipado para consultas. */
  @SuppressWarnings("unchecked")
  public static <Q extends Query<R>, R> Registration query(
      Class<Q> type, Class<R> result, QueryHandler<Q, R> handler) {
    return new Registration(type, result, req -> handler.handle((Q) req));
  }

  @Override
  public <R> R send(Command<R> command) {
    return dispatch(command);
  }

  @Override
  public <R> R ask(Query<R> query) {
    return dispatch(query);
  }

  public boolean handles(Class<?> requestType) {
    return handlers.containsKey(requestType);
  }

  @SuppressWarnings("unchecked")
  private <R> R dispatch(Request<R> request) {
    Objects.requireNonNull(request, "request");
    Registration r = handlers.get(request.getClass());
    if (r == null) {
      throw new HandlerNotFoundException(request.getClass());
    }
    Invocation<R> invocation =
        new Invocation<>(request, operationName(request.getClass()), r.resultType());
    return run(invocation, 0, () -> (R) r.handler().apply(request));
  }

  private <R> R run(Invocation<R> invocation, int index, PipelineBehavior.Next<R> terminal) {
    if (index == behaviors.size()) {
      return terminal.proceed();
    }
    return behaviors.get(index).handle(invocation, () -> run(invocation, index + 1, terminal));
  }

  /**
   * Nombre de operación de un tipo de petición usado en spans, métricas, auditoría y claves de
   * idempotencia: el nombre de la clase sin el sufijo {@code Command}/{@code Query} en kebab-case
   * ({@code CreatePaymentCommand} es {@code create-payment}).
   */
  public static String operationName(Class<?> requestType) {
    return kebab(requestType.getSimpleName());
  }

  static String kebab(String name) {
    String n =
        name.endsWith("Command")
            ? name.substring(0, name.length() - 7)
            : name.endsWith("Query") ? name.substring(0, name.length() - 5) : name;
    return n.replaceAll("([a-z0-9])([A-Z])", "$1-$2").toLowerCase(Locale.ROOT);
  }
}
