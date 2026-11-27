package pe.axiz.kit.jpa;

import jakarta.persistence.Column;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.Version;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import pe.axiz.core.id.UuidV7;

/**
 * Entidad JPA base: id UUIDv7 ordenado por tiempo y asignado por la aplicación (apto para índices,
 * conocido antes del insert) y bloqueo optimista. Una versión null significa "nuevo", lo que
 * permite a Spring Data decidir entre persist y merge sin un SELECT adicional.
 */
@MappedSuperclass
public abstract class BaseEntity {

  @Id
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id = UuidV7.generate();

  @Version
  @Column(name = "version")
  private @Nullable Long version;

  public UUID getId() {
    return id;
  }

  protected void setId(UUID id) {
    this.id = Objects.requireNonNull(id);
  }

  public @Nullable Long getVersion() {
    return version;
  }

  public boolean isNew() {
    return version == null;
  }

  @Override
  public final boolean equals(@Nullable Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof BaseEntity other) || effectiveClass(this) != effectiveClass(other)) {
      return false;
    }
    return id.equals(other.id);
  }

  @Override
  public final int hashCode() {
    return id.hashCode();
  }

  /** Clase real detrás de un proxy lazy (los proxies extienden la clase de la entidad). */
  private static Class<?> effectiveClass(Object o) {
    Class<?> c = o.getClass();
    while (c.getSuperclass() != null
        && (c.getName().contains("$HibernateProxy") || c.getName().contains("$$"))) {
      c = c.getSuperclass();
    }
    return c;
  }
}
