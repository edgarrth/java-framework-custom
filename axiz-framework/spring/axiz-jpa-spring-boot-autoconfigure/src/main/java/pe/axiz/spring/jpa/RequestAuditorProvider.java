package pe.axiz.spring.jpa;

import java.util.Optional;
import pe.axiz.core.commons.RequestContext;
import pe.axiz.core.commons.RequestMetadata;
import pe.axiz.core.persistence.jpa.AuditorProvider;

/**
 * Auditor of {@code createdBy}/{@code updatedBy}: the end user, or the calling application for
 * machine-to-machine requests, as authenticated by the security starter; {@code system} otherwise.
 */
public class RequestAuditorProvider implements AuditorProvider {

  @Override
  public Optional<String> currentAuditor() {
    RequestMetadata m = RequestContext.get();
    if (m == null) {
      return Optional.of("system");
    }
    if (m.subject() != null) {
      return Optional.of(m.subject());
    }
    return Optional.of(m.clientId() != null ? m.clientId() : "system");
  }
}
