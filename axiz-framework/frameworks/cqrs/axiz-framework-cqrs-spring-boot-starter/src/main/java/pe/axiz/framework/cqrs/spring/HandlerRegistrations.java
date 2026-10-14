package pe.axiz.framework.cqrs.spring;

import java.util.ArrayList;
import java.util.List;
import org.springframework.aop.support.AopUtils;
import org.springframework.core.ResolvableType;
import pe.axiz.framework.cqrs.Buses;
import pe.axiz.framework.cqrs.CommandHandler;
import pe.axiz.framework.cqrs.QueryHandler;
import pe.axiz.framework.cqrs.ReactiveCommandHandler;
import pe.axiz.framework.cqrs.ReactiveQueryHandler;

/**
 * Resolves request and result types of handler beans from their generics on the target class (so
 * transactional proxies work). An unresolvable handler is a startup error.
 */
final class HandlerRegistrations {

  private HandlerRegistrations() {}

  @SuppressWarnings({"unchecked", "rawtypes"})
  static List<Buses.Registration> of(
      List<CommandHandler<?, ?>> commands, List<QueryHandler<?, ?>> queries) {
    List<Buses.Registration> out = new ArrayList<>();
    for (CommandHandler handler : commands) {
      ResolvableType t =
          ResolvableType.forClass(AopUtils.getTargetClass(handler)).as(CommandHandler.class);
      out.add(
          new Buses.Registration(
              resolve(t, 0, handler),
              resolve(t, 1, handler),
              req -> handler.handle((pe.axiz.framework.cqrs.Command) req)));
    }
    for (QueryHandler handler : queries) {
      ResolvableType t =
          ResolvableType.forClass(AopUtils.getTargetClass(handler)).as(QueryHandler.class);
      out.add(
          new Buses.Registration(
              resolve(t, 0, handler),
              resolve(t, 1, handler),
              req -> handler.handle((pe.axiz.framework.cqrs.Query) req)));
    }
    return out;
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  static List<ReactiveBuses.Registration> reactive(
      List<ReactiveCommandHandler<?, ?>> commands, List<ReactiveQueryHandler<?, ?>> queries) {
    List<ReactiveBuses.Registration> out = new ArrayList<>();
    for (ReactiveCommandHandler handler : commands) {
      ResolvableType t =
          ResolvableType.forClass(AopUtils.getTargetClass(handler))
              .as(ReactiveCommandHandler.class);
      out.add(
          new ReactiveBuses.Registration(
              resolve(t, 0, handler),
              resolve(t, 1, handler),
              req -> handler.handle((pe.axiz.framework.cqrs.Command) req)));
    }
    for (ReactiveQueryHandler handler : queries) {
      ResolvableType t =
          ResolvableType.forClass(AopUtils.getTargetClass(handler)).as(ReactiveQueryHandler.class);
      out.add(
          new ReactiveBuses.Registration(
              resolve(t, 0, handler),
              resolve(t, 1, handler),
              req -> handler.handle((pe.axiz.framework.cqrs.Query) req)));
    }
    return out;
  }

  /** A request type served by both a blocking and a reactive handler is a startup error. */
  static void requireSingleStyle(
      List<Buses.Registration> blocking, List<ReactiveBuses.Registration> reactive) {
    for (ReactiveBuses.Registration r : reactive) {
      for (Buses.Registration b : blocking) {
        if (b.requestType().equals(r.requestType())) {
          throw new IllegalStateException(
              r.requestType().getName() + " has both a blocking and a reactive handler; keep one");
        }
      }
    }
  }

  private static Class<?> resolve(ResolvableType type, int index, Object handler) {
    Class<?> c = type.getGeneric(index).resolve();
    if (c == null) {
      throw new IllegalStateException(
          "Cannot resolve the generic types of handler "
              + AopUtils.getTargetClass(handler).getName()
              + "; declare them on the class (e.g. implements CommandHandler<CreateCard, CardId>)");
    }
    return c;
  }
}
