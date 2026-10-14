package pe.axiz.core.persistence.jpa;

import jakarta.persistence.Column;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.MappedSuperclass;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

/** Entity with creation and modification audit columns (UTC instants and principal names). */
@MappedSuperclass
@EntityListeners(AuditListener.class)
public abstract class AuditableEntity extends BaseEntity {

  @Column(name = "created_at", nullable = false, updatable = false)
  private @Nullable Instant createdAt;

  @Column(name = "created_by", length = 100, updatable = false)
  private @Nullable String createdBy;

  @Column(name = "updated_at")
  private @Nullable Instant updatedAt;

  @Column(name = "updated_by", length = 100)
  private @Nullable String updatedBy;

  public @Nullable Instant getCreatedAt() {
    return createdAt;
  }

  public @Nullable String getCreatedBy() {
    return createdBy;
  }

  public @Nullable Instant getUpdatedAt() {
    return updatedAt;
  }

  public @Nullable String getUpdatedBy() {
    return updatedBy;
  }

  void markCreated(Instant at, String by) {
    this.createdAt = at;
    this.createdBy = by;
    this.updatedAt = at;
    this.updatedBy = by;
  }

  void markUpdated(Instant at, String by) {
    this.updatedAt = at;
    this.updatedBy = by;
  }
}
