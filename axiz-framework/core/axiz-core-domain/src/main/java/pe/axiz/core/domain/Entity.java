package pe.axiz.core.domain;

import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Domain entity with identity. Two entities are equal when they have the same type and id.
 *
 * @param <I> id type
 */
public abstract class Entity<I> {

  protected abstract I id();

  // The exact concrete type on purpose, not instanceof: a Customer and an Account with the same id
  // are different entities although both extend Entity. equals is final, so symmetry holds.
  @SuppressWarnings("EqualsGetClass")
  @Override
  public final boolean equals(@Nullable Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    return Objects.equals(id(), ((Entity<?>) o).id());
  }

  @Override
  public final int hashCode() {
    return Objects.hash(getClass(), id());
  }
}
