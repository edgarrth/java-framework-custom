package pe.axiz.spring.jpa;

import java.util.Optional;
import pe.axiz.core.context.RequestContext;
import pe.axiz.core.context.RequestMetadata;
import pe.axiz.kit.jpa.AuditorProvider;

/**
 * Auditor de {@code createdBy}/{@code updatedBy}: el usuario final, o la aplicación invocante en
 * solicitudes de máquina a máquina, según la autenticación del starter de seguridad; un resultado
 * vacío cuando no hay ninguna identidad autenticada disponible.
 */
public class RequestAuditorProvider implements AuditorProvider {

  @Override
  public Optional<String> currentAuditor() {
    RequestMetadata m = RequestContext.get();
    if (m == null) {
      return Optional.empty();
    }
    String subject = m.subject();
    if (subject != null && !subject.isBlank()) {
      return Optional.of(subject);
    }
    return Optional.ofNullable(m.clientId()).filter(id -> !id.isBlank());
  }
}
