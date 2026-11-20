package pe.axiz.kit.jpa;

import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

/** Fills the audit columns from the injected auditor provider and clock. */
public class AuditListener {

  static final String UNATTRIBUTED = "unattributed";
  private final AuditorProvider auditor;
  private final Clock clock;

  public AuditListener(AuditorProvider auditor, Clock clock) {
    Objects.requireNonNull(auditor);
    Objects.requireNonNull(clock);
    super();
    this.auditor = auditor;
    this.clock = clock;
  }

  @PrePersist
  public void onCreate(Object entity) {
    if (entity instanceof AuditableEntity e) {
      Instant now = clock.instant();
      e.markCreated(
          now, auditor.currentAuditor().filter(value -> !value.isBlank()).orElse(UNATTRIBUTED));
    }
  }

  @PreUpdate
  public void onUpdate(Object entity) {
    if (entity instanceof AuditableEntity e) {
      e.markUpdated(
          clock.instant(),
          auditor.currentAuditor().filter(value -> !value.isBlank()).orElse(UNATTRIBUTED));
    }
  }
}
