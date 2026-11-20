package pe.axiz.adapter.tokenization.redis;

import java.util.Objects;
import java.util.UUID;

/** Server-owned deployment instance and process incarnation; never supplied by a request. */
public record TokenBudgetOwner(String instance, UUID incarnation) {
  private static final TokenBudgetOwner PROCESS =
      new TokenBudgetOwner("process-" + UUID.randomUUID(), UUID.randomUUID());

  public TokenBudgetOwner {
    if (instance == null || !instance.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,127}"))
      throw new IllegalArgumentException("Invalid token budget instance identity");
    Objects.requireNonNull(incarnation);
  }

  /** Default identity shared by all budgets in this JVM; log deployment mapping at startup. */
  public static TokenBudgetOwner process() {
    return PROCESS;
  }
}
