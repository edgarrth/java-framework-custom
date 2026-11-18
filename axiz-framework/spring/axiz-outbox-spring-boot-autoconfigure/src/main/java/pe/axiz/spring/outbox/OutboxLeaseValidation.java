package pe.axiz.spring.outbox;

import java.time.Duration;
import java.util.Optional;
import org.slf4j.LoggerFactory;
import pe.axiz.core.concurrent.LeaseTiming;
import pe.axiz.core.messaging.BoundedPublisher;
import pe.axiz.core.messaging.EventPublisher;

/** Relay safety uses the effective publisher, not broker-property guesses or a wall-clock lease. */
final class OutboxLeaseValidation {
  private OutboxLeaseValidation() {}

  static Duration budget(EventPublisher publisher, AxizOutboxProperties.Relay relay) {
    var bound = BoundedPublisher.of(publisher);
    if (!bound.hasUnbounded() && relay.publishBudget() != null)
      LoggerFactory.getLogger(OutboxLeaseValidation.class)
          .warn(
              "axiz.outbox.relay.publish-budget is ignored because all publisher routes declare"
                  + " their bound");
    Duration budget = bound.budget(Optional.ofNullable(relay.publishBudget()));
    validate(relay.leaseDuration(), budget, relay.claimAllowance());
    return budget;
  }

  static void validate(Duration lease, Duration budget, Duration claimAllowance) {
    if (!budget.isPositive() || !claimAllowance.isPositive())
      throw new IllegalArgumentException("Publication budget and claim allowance must be positive");
    Duration margin = LeaseTiming.margin(lease);
    Duration available = lease.minus(margin).minus(claimAllowance);
    if (available.compareTo(budget) < 0)
      throw new IllegalArgumentException(
          "Outbox lease "
              + lease
              + " minus margin "
              + margin
              + " must cover publication budget "
              + budget
              + " plus claim allowance "
              + claimAllowance);
    if (available.compareTo(budget.multipliedBy(2)) < 0)
      LoggerFactory.getLogger(OutboxLeaseValidation.class)
          .warn(
              "After reserving the configured claim allowance, the outbox lease cannot cover two"
                  + " sends each consuming its maximum declared budget. Actual publication count"
                  + " depends on claim and send durations");
  }
}
