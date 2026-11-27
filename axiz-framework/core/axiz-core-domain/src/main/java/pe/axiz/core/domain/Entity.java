package pe.axiz.core.domain;

import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Entidad de dominio con identidad. Dos entidades son iguales cuando tienen el mismo tipo e id. Se
 * asigna una identidad estable y no null al crear la entidad, antes de colocarla en colecciones.
 * Una identidad asignada o cambiada después invalida la pertenencia a colecciones basadas en hash;
 * los ids null del mismo tipo concreto se consideran iguales y no se soportan para entidades
 * transitorias distintas.
 *
 * @param <I> tipo del id
 */
public abstract class Entity<I> {

  protected abstract I id();

  // Se usa a propósito el tipo concreto exacto, no instanceof: un Customer y un Account con el
  // mismo id son entidades distintas aunque ambos extiendan Entity. equals es final, así que se
  // mantiene la simetría.
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
