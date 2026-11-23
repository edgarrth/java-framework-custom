package pe.axiz.core.audit;

import java.time.Clock;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.UnaryOperator;

/**
 * Cabezas de cadena locales al proceso. Una instantánea de este estado no certifica la entrega a un
 * destino.
 */
public final class InMemoryAuditChainState implements AuditChainState {
  private final Clock clock;
  private final int maxChains;
  private final Map<String, Head> chains = new HashMap<>();

  public InMemoryAuditChainState(Clock clock, int maxChains) {
    this.clock = Objects.requireNonNull(clock);
    if (maxChains < 1 || maxChains > 10000)
      throw new IllegalArgumentException("Invalid audit chain limit");
    this.maxChains = maxChains;
  }

  private synchronized Head head(String chainId) {
    AuditIntegrity.validateChain(chainId);
    Head existing = chains.get(chainId);
    if (existing != null) return existing;
    if (chains.size() >= maxChains) throw new IllegalStateException("Audit chain capacity reached");
    var created =
        new Head(
            new AuditIntegrity(chainId, 0, SealPreimage.GENESIS, SealPreimage.GENESIS, null, null));
    chains.put(chainId, created);
    return created;
  }

  @Override
  public AuditIntegrity advance(String chainId, UnaryOperator<AuditIntegrity> step) {
    Head holder = head(chainId);
    synchronized (holder) {
      AuditIntegrity next = Objects.requireNonNull(step.apply(holder.value), "chain successor");
      holder.value.requireSuccessor(next);
      holder.value = next;
      return next;
    }
  }

  @Override
  public ChainCheckpoint checkpoint(String chainId) {
    Head holder = head(chainId);
    synchronized (holder) {
      return new ChainCheckpoint(
          chainId, holder.value.sequence(), holder.value.hash(), clock.instant());
    }
  }

  private static final class Head {
    private AuditIntegrity value;

    private Head(AuditIntegrity value) {
      this.value = value;
    }
  }
}
