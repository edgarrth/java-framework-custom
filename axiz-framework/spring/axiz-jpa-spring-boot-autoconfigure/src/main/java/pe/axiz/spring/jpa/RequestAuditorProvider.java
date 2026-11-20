package pe.axiz.spring.jpa;

import java.util.Optional;
import pe.axiz.core.context.RequestContext;
import pe.axiz.core.context.RequestMetadata;
import pe.axiz.kit.jpa.AuditorProvider;

/**
 * Auditor of {@code createdBy}/{@code updatedBy}: the end user, or the calling application for
 * machine-to-machine requests, as authenticated by the security starter; an empty result when no
 * authenticated identity is available.
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
