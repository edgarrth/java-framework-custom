package pe.axiz.spring.r2dbc;

import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedBy;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.annotation.Version;
import org.springframework.data.domain.Persistable;
import pe.axiz.core.id.UuidV7;

/**
 * Base of Spring Data R2DBC rows: time-ordered UUIDv7 id assigned on creation, optimistic locking
 * and auditing columns filled from the caller of the request ({@code id}, {@code version}, {@code
 * created_at}, {@code created_by}, {@code updated_at}, {@code updated_by}). A row is new while its
 * version is null, so {@code save} inserts it once and updates it afterwards.
 */
public abstract class AuditableRow implements Persistable<UUID> {

  @Id private UUID id = UuidV7.generate();
  @Version private @Nullable Long version;
  @CreatedDate private @Nullable Instant createdAt;
  @CreatedBy private @Nullable String createdBy;
  @LastModifiedDate private @Nullable Instant updatedAt;
  @LastModifiedBy private @Nullable String updatedBy;

  @Override
  public UUID getId() {
    return id;
  }

  /** Replaces the generated id, e.g. with an id received from another system. */
  protected void setId(UUID id) {
    this.id = id;
  }

  @Override
  public boolean isNew() {
    return version == null;
  }

  public @Nullable Long getVersion() {
    return version;
  }

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
}
