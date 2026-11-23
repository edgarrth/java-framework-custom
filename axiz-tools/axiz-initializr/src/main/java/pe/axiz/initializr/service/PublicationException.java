package pe.axiz.initializr.service;

import java.util.UUID;

/**
 * Estado de publicación seguro. Un invocador puede reintentar la misma operación con el mismo
 * proyecto.
 */
public final class PublicationException extends IllegalStateException {
  /**
   * Última frontera confirmada; una escritura desconocida debe conciliarse previo a reintentarla.
   */
  public enum Phase {
    PREFLIGHT,
    REPOSITORY_CREATE_UNKNOWN,
    CONTENT_PENDING,
    BRANCH_UPDATE_UNKNOWN,
    CONFLICT
  }

  private final UUID operation;
  private final Phase phase;
  private final String repository;

  PublicationException(UUID operation, Phase phase, String repository, RuntimeException cause) {
    super("GitHub publication is incomplete; retry the same operation and project", cause);
    this.operation = operation;
    this.phase = phase;
    this.repository = repository;
  }

  public UUID operation() {
    return operation;
  }

  public Phase phase() {
    return phase;
  }

  public String repository() {
    return repository;
  }
}
