package pe.axiz.codegen.model;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

/** Architectural style (layer 3) and the stacks it supports in v1. */
public enum Framework {
  LAYERED(EnumSet.of(Stack.SERVLET)),
  HEXAGONAL(EnumSet.of(Stack.SERVLET)),
  CQRS(EnumSet.of(Stack.SERVLET, Stack.REACTIVE)),
  EDA(EnumSet.of(Stack.SERVLET, Stack.REACTIVE)),
  BFF(EnumSet.of(Stack.SERVLET, Stack.REACTIVE)),
  BATCH(EnumSet.of(Stack.SERVLET)),
  /** Platform archetypes (library, starter) have no style. */
  NONE(EnumSet.of(Stack.SERVLET));

  private final Set<Stack> stacks;

  Framework(Set<Stack> stacks) {
    this.stacks = stacks;
  }

  public Set<Stack> stacks() {
    return stacks;
  }

  public String id() {
    return name().toLowerCase(Locale.ROOT);
  }

  /** Maven artifact of the style starter, or null for {@link #NONE}. */
  public String starter() {
    return this == NONE ? "" : "axiz-framework-" + id() + "-spring-boot-starter";
  }

  public static Framework of(String id) {
    return valueOf(id.toUpperCase(Locale.ROOT));
  }
}
