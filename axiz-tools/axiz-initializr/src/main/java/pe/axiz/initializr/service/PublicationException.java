package pe.axiz.initializr.service;

import java.util.UUID;

/** Safe publication status. A caller can retry the same operation and identical project. */
public final class PublicationException extends IllegalStateException {
  /** Last confirmed boundary; an unknown write must be reconciled before retrying it. */
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
