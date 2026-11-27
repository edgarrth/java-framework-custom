package pe.axiz.spring.tokenization.vault;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;
import pe.axiz.core.audit.AsyncStrictAuditPublisher;
import pe.axiz.core.concurrent.Stages;
import pe.axiz.core.tokenization.vault.AsyncTokenizationAudit;
import pe.axiz.core.tokenization.vault.TokenAuditEntry;

/** Auditoría confirmada nativa; sin wrapper alrededor del publicador bloqueante y sin respaldo. */
public final class AsyncStrictTokenizationAudit implements AsyncTokenizationAudit {
  private final AsyncStrictAuditPublisher publisher;
  private final TokenAuditEvents events;

  public AsyncStrictTokenizationAudit(
      AsyncStrictAuditPublisher publisher, String service, Supplier<UUID> ids) {
    this.publisher = Objects.requireNonNull(publisher);
    events = new TokenAuditEvents(service, ids);
  }

  @Override
  public CompletionStage<Void> publishConfirmed(TokenAuditEntry entry) {
    return Stages.call(() -> publisher.publish(events.map(entry)));
  }
}
