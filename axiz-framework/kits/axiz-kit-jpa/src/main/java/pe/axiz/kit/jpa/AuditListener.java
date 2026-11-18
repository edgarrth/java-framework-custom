package pe.axiz.kit.jpa;

import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

/** Fills the audit columns from the injected auditor provider and clock. */
public class AuditListener {

  static final String SYSTEM = "system";
  private final AuditorProvider auditor;
  private final Clock clock;

  public AuditListener(AuditorProvider auditor, Clock clock) {
    this.auditor = Objects.requireNonNull(auditor);
    this.clock = Objects.requireNonNull(clock);
  }

  @PrePersist
  public void onCreate(Object entity) {
    if (entity instanceof AuditableEntity e) {
      Instant now = clock.instant();
      e.markCreated(now, auditor.currentAuditor().orElse(SYSTEM));
    }
  }

  @PreUpdate
  public void onUpdate(Object entity) {
    if (entity instanceof AuditableEntity e) {
      e.markUpdated(clock.instant(), auditor.currentAuditor().orElse(SYSTEM));
    }
  }
}
