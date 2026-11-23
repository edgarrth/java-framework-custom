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
 * Base de las filas de Spring Data R2DBC: id UUIDv7 ordenado en el tiempo asignado en la creación,
 * bloqueo optimista y columnas de auditoría completadas a partir del invocante de la solicitud
 * ({@code id}, {@code version}, {@code created_at}, {@code created_by}, {@code updated_at}, {@code
 * updated_by}). Una fila es nueva mientras su versión es null, por lo que {@code save} la inserta
 * una vez y después la actualiza.
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

  /** Reemplaza el id generado, p. ej. por un id recibido de otro sistema. */
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
