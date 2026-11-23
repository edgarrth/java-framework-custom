package pe.axiz.codegen.model;

import java.util.Locale;
import java.util.Set;

/** Estructura de la aplicación y los stacks de ejecución que soporta. */
public enum Structure {
  LAYERED(true),
  HEXAGONAL(true),
  EDA(true),
  BFF(true),
  BATCH(false),
  /** Los arquetipos de plataforma (library, starter) no tienen estilo. */
  NONE(false);

  private final boolean reactive;

  Structure(boolean reactive) {
    this.reactive = reactive;
  }

  /** Los stacks que soporta el estilo; un conjunto no modificable que siempre incluye servlet. */
  public Set<Stack> stacks() {
    return reactive ? Set.of(Stack.SERVLET, Stack.REACTIVE) : Set.of(Stack.SERVLET);
  }

  public String id() {
    return name().toLowerCase(Locale.ROOT);
  }

  /** Artefacto Maven del starter del estilo, o null para {@link #NONE}. */
  public String starter() {
    return this == NONE ? "" : "axiz-structure-" + id() + "-spring-boot-starter";
  }

  public static Structure of(String id) {
    return valueOf(id.toUpperCase(Locale.ROOT));
  }
}
