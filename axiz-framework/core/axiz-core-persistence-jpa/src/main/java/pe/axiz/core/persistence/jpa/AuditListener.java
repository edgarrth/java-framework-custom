package pe.axiz.core.persistence.jpa;

import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import java.time.Instant;

/** Fills the audit columns from the registered auditor provider and clock. */
public class AuditListener {

  static final String SYSTEM = "system";

  @PrePersist
  public void onCreate(Object entity) {
    if (entity instanceof AuditableEntity e) {
      Instant now = AuditingRegistry.clock().instant();
      e.markCreated(now, AuditingRegistry.auditor().currentAuditor().orElse(SYSTEM));
    }
  }

  @PreUpdate
  public void onUpdate(Object entity) {
    if (entity instanceof AuditableEntity e) {
      e.markUpdated(
          AuditingRegistry.clock().instant(),
          AuditingRegistry.auditor().currentAuditor().orElse(SYSTEM));
    }
  }
}
