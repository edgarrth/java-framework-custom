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
 * Reactive command and query buses: the same pipeline as the blocking buses (observation,
 * validation, authorization, idempotency, transaction, audit, metrics) composed as operators on the
 * handler's {@code Mono}, so no step blocks an event loop. The transaction is a reactive one
 * (R2DBC), read-only for queries.
 */
public final class ReactiveBuses implements ReactiveCommandBus, ReactiveQueryBus {

  /**
   * A reactive handler and the types it serves.
   *
   * @param requestType request class
   * @param resultType result class
   * @param handler handler function
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
