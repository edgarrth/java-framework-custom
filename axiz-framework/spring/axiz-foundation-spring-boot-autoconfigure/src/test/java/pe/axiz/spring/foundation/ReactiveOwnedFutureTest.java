package pe.axiz.spring.foundation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import reactor.test.publisher.TestPublisher;

class ReactiveOwnedFutureTest {
  @Test
  void cancellationReleasesLateValueAndSuccessfulDeliveryTransfersOwnership() {
    var discarded = new AtomicInteger();
    var source =
        TestPublisher.<Object>createNoncompliant(TestPublisher.Violation.DEFER_CANCELLATION);
    var future =
        ReactiveStages.toOwnedFuture(
            source.mono(), Object.class, ignored -> discarded.incrementAndGet());
    assertTrue(future.cancel(true));
    source.next(new Object()).complete();
    assertEquals(1, discarded.get());
    var second = TestPublisher.<Object>create();
    var success =
        ReactiveStages.toOwnedFuture(
            second.mono(), Object.class, ignored -> discarded.incrementAndGet());
    Object result = new Object();
    second.next(result).complete();
    assertSame(result, success.join());
    assertEquals(1, discarded.get());
  }
}
