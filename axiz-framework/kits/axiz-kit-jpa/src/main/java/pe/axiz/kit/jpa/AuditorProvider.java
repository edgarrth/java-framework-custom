package pe.axiz.kit.jpa;

import java.util.Optional;

/** Quién está cambiando los datos: el nombre del principal autenticado o el sistema. */
@FunctionalInterface
public interface AuditorProvider {

  Optional<String> currentAuditor();
}
