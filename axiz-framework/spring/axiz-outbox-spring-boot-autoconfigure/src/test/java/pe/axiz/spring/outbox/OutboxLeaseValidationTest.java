package pe.axiz.spring.outbox;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import pe.axiz.core.concurrent.LeaseTiming;

class OutboxLeaseValidationTest {
  @Test
  void exactUsableBudgetIsAcceptedAndOneNanosecondLessFails() {
    Duration lease = Duration.ofSeconds(10);
    Duration margin = LeaseTiming.margin(lease);
    Duration allowance = Duration.ofSeconds(2);
    Duration budget = lease.minus(margin).minus(allowance);
    assertDoesNotThrow(() -> OutboxLeaseValidation.validate(lease, budget, allowance));
    assertThrows(
        IllegalArgumentException.class,
        () -> OutboxLeaseValidation.validate(lease, budget.plusNanos(1), allowance));
    assertThrows(
        IllegalArgumentException.class,
        () -> OutboxLeaseValidation.validate(lease, Duration.ZERO, allowance));
    assertThrows(
        IllegalArgumentException.class,
        () -> OutboxLeaseValidation.validate(lease, budget, Duration.ZERO));
  }
}
