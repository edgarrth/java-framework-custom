package pe.axiz.codegen.model;

import java.util.Locale;
import java.util.Set;

/** Architectural style (layer 3) and the stacks it supports in v1. */
public enum Structure {
  LAYERED(true),
  HEXAGONAL(true),
  EDA(true),
  BFF(true),
  BATCH(false),
  /** Platform archetypes (library, starter) have no style. */
  NONE(false);

  private final boolean reactive;

  Structure(boolean reactive) {
    this.reactive = reactive;
  }

  /** The stacks the style supports; an unmodifiable set, servlet always included. */
  public Set<Stack> stacks() {
    return reactive ? Set.of(Stack.SERVLET, Stack.REACTIVE) : Set.of(Stack.SERVLET);
  }

  public String id() {
    return name().toLowerCase(Locale.ROOT);
  }

  /** Maven artifact of the style starter, or null for {@link #NONE}. */
  public String starter() {
    return this == NONE ? "" : "axiz-structure-" + id() + "-spring-boot-starter";
  }

  public static Structure of(String id) {
    return valueOf(id.toUpperCase(Locale.ROOT));
  }
}
