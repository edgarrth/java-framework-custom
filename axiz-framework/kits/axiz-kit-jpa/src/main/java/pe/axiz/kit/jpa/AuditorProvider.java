package pe.axiz.kit.jpa;

import java.util.Optional;

/** Who is changing data: the authenticated principal name, or the system. */
@FunctionalInterface
public interface AuditorProvider {

  Optional<String> currentAuditor();
}
