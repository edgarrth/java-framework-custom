package pe.axiz.extension.cqrs.spring;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import org.reactivestreams.Publisher;
import pe.axiz.extension.cqrs.Buses;
import pe.axiz.extension.cqrs.Command;
import pe.axiz.extension.cqrs.HandlerNotFoundException;
import pe.axiz.extension.cqrs.Invocation;
import pe.axiz.extension.cqrs.Query;
import pe.axiz.extension.cqrs.Request;
import reactor.core.publisher.Mono;

/**
 * Buses reactivos de comandos y consultas: el mismo pipeline que los buses bloqueantes
 * (observación, validación, autorización, idempotencia, transacción, auditoría, métricas) compuesto
 * como operadores sobre el {@code Mono} del handler, de modo que ningún paso bloquea un event loop.
 * La transacción es reactiva (R2DBC) y de solo lectura para las consultas.
 */
public final class ReactiveBuses implements ReactiveCommandBus, ReactiveQueryBus {

  /**
   * Handler reactivo y los tipos que atiende.
   *
   * @param requestType clase de la petición
   * @param resultType clase del resultado
   * @param handler función del handler
   */
  public record Registration(
      Class<?> requestType, Class<?> resultType, Function<Object, Publisher<?>> handler) {}

  private final Map<Class<?>, Registration> handlers;
  private final List<ReactivePipelineBehavior> behaviors;

  public ReactiveBuses(List<Registration> registrations, List<ReactivePipelineBehavior> behaviors) {
    Map<Class<?>, Registration> map = new LinkedHashMap<>();
    for (Registration r : registrations) {
      if (map.put(r.requestType(), r) != null) {
        throw new IllegalStateException("Two handlers for " + r.requestType().getName());
      }
    }
    this.handlers = Map.copyOf(map);
    this.behaviors =
        behaviors.stream()
            .sorted(Comparator.comparingInt(ReactivePipelineBehavior::order))
            .toList();
  }

  @Override
  public <R> Mono<R> send(Command<R> command) {
    return dispatch(command);
  }

  @Override
  public <R> Mono<R> ask(Query<R> query) {
    return dispatch(query);
  }

  public boolean handles(Class<?> requestType) {
    return handlers.containsKey(requestType);
  }

  @SuppressWarnings("unchecked")
  private <R> Mono<R> dispatch(Request<R> request) {
    Objects.requireNonNull(request, "request");
    Registration r = handlers.get(request.getClass());
    if (r == null) {
      return Mono.error(new HandlerNotFoundException(request.getClass()));
    }
    Invocation<R> invocation =
        new Invocation<>(request, Buses.operationName(request.getClass()), r.resultType());
    return run(invocation, 0, () -> Mono.from((Publisher<R>) r.handler().apply(request)));
  }

  private <R> Mono<R> run(
      Invocation<R> invocation, int index, ReactivePipelineBehavior.Next<R> terminal) {
    if (index == behaviors.size()) {
      return Mono.defer(terminal::proceed);
    }
    return Mono.defer(
        () -> behaviors.get(index).handle(invocation, () -> run(invocation, index + 1, terminal)));
  }
}
