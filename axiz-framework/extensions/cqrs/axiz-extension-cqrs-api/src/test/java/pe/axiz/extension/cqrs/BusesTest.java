package pe.axiz.extension.cqrs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class BusesTest {

  record CreateCardCommand(String holder) implements Command<String> {}

  record CardQuery(String id) implements Query<String> {}

  record UnknownCommand() implements Command<Void> {}

  static PipelineBehavior tracing(int order, List<String> log) {
    return new PipelineBehavior() {
      @Override
      public int order() {
        return order;
      }

      @Override
      public <R> R handle(Invocation<R> invocation, Next<R> next) {
        log.add(order + ":" + invocation.name() + ":" + invocation.isCommand());
        return next.proceed();
      }
    };
  }

  @Test
  void pipelineRunsInOrderAndHandlersAreResolved() {
    List<String> log = new ArrayList<>();
    Buses buses =
        new Buses(
            List.of(
                Buses.command(CreateCardCommand.class, String.class, c -> "card-of-" + c.holder()),
                Buses.query(CardQuery.class, String.class, q -> "card " + q.id())),
            List.of(
                tracing(PipelineBehavior.TRANSACTION, log),
                tracing(PipelineBehavior.VALIDATION, log)));
    assertEquals("card-of-ana", buses.send(new CreateCardCommand("ana")));
    assertEquals(List.of("100:create-card:true", "400:create-card:true"), log);
    assertEquals("card 1", buses.ask(new CardQuery("1")));
    assertThrows(HandlerNotFoundException.class, () -> buses.send(new UnknownCommand()));
    assertThrows(
        IllegalStateException.class,
        () ->
            new Buses(
                List.of(
                    Buses.query(CardQuery.class, String.class, q -> "a"),
                    Buses.query(CardQuery.class, String.class, q -> "b")),
                List.of()));
  }
}
