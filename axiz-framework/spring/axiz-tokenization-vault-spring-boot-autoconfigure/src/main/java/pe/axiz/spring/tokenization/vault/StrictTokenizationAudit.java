package pe.axiz.spring.tokenization.vault;

import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import pe.axiz.core.audit.StrictAuditPublisher;
import pe.axiz.core.tokenization.vault.TokenAuditEntry;
import pe.axiz.core.tokenization.vault.TokenizationAudit;

/** Requires durable confirmation and propagates failure without a log or queue fallback. */
public final class StrictTokenizationAudit implements TokenizationAudit {
  private final StrictAuditPublisher publisher;
  private final TokenAuditEvents events;

  public StrictTokenizationAudit(
      StrictAuditPublisher publisher, String service, Supplier<UUID> ids) {
    this.publisher = Objects.requireNonNull(publisher);
    events = new TokenAuditEvents(service, ids);
  }

  @Override
  public void publishConfirmed(TokenAuditEntry entry) {
    publisher.publish(events.map(entry));
  }
}
