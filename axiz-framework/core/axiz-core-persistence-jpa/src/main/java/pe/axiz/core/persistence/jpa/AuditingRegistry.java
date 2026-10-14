package pe.axiz.core.persistence.jpa;

import java.time.Clock;
import java.util.Objects;
import java.util.Optional;

/**
 * Holds the auditor provider and clock used by {@link AuditListener}. JPA instantiates listeners
 * itself, so the runtime (Spring starter, Quarkus extension) registers them here at startup.
 */
public final class AuditingRegistry {

  private static volatile AuditorProvider auditor = Optional::empty;
  private static volatile Clock clock = Clock.systemUTC();

  private AuditingRegistry() {}

  public static void register(AuditorProvider provider, Clock c) {
    auditor = Objects.requireNonNull(provider);
    clock = Objects.requireNonNull(c);
  }

  static AuditorProvider auditor() {
    return auditor;
  }

  static Clock clock() {
    return clock;
  }

  /** Restores defaults (tests). */
  public static void reset() {
    auditor = Optional::empty;
    clock = Clock.systemUTC();
  }
}
