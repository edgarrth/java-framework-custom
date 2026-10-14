package pe.axiz.framework.hexagonal.spring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.aop.AopAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.event.EventListener;
import pe.axiz.core.domain.DomainEvent;
import pe.axiz.framework.hexagonal.DomainEvents;
import pe.axiz.framework.hexagonal.annotation.UseCase;

class HexagonalTest {

  record CardBlocked(String eventId, Instant occurredAt, String aggregateId)
      implements DomainEvent {}

  @UseCase("block-card")
  public static class BlockCard {
    public String execute(String cardId) {
      DomainEvents.raise(List.of(new CardBlocked("e-" + cardId, Instant.EPOCH, cardId)));
      return cardId;
    }
  }

  public static class Listener {
    final List<String> seen = new CopyOnWriteArrayList<>();

    @EventListener
    public void on(CardBlocked e) {
      seen.add(e.aggregateId());
    }
  }

  @Test
  void useCasesArePublishedAfterExecution() {
    new ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(AopAutoConfiguration.class, AxizHexagonalAutoConfiguration.class))
        .withBean(BlockCard.class)
        .withBean(Listener.class)
        .run(
            ctx -> {
              BlockCard useCase = ctx.getBean(BlockCard.class);
              assertTrue(AopUtils.isAopProxy(useCase));
              assertEquals("c-1", useCase.execute("c-1"));
              assertEquals(List.of("c-1"), ctx.getBean(Listener.class).seen);
            });
  }
}
