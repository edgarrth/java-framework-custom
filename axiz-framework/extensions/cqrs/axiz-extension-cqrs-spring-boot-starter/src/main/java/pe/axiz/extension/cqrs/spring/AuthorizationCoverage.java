package pe.axiz.extension.cqrs.spring;

import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import pe.axiz.extension.cqrs.AccessPolicies;

/** Startup check of the commands and queries nothing protects. */
final class AuthorizationCoverage {

  private static final Logger LOG = LoggerFactory.getLogger(AuthorizationCoverage.class);

  private AuthorizationCoverage() {}

  /**
   * Checks that every command and query declares who may send it.
   *
   * @param mode {@code warn} (log them), {@code fail} (stop startup) or {@code allow}
   */
  static void verify(Collection<Class<?>> requestTypes, AccessPolicies policies, String mode) {
    if ("allow".equalsIgnoreCase(mode)) {
      return;
    }
    List<Class<?>> open = policies.unprotected(requestTypes);
    if (open.isEmpty()) {
      return;
    }
    String names = open.stream().map(Class::getName).collect(Collectors.joining(", "));
    String message =
        "Commands/queries without RequiresAuthority, AccessPolicy or OpenAccess: " + names;
    if ("fail".equalsIgnoreCase(mode)) {
      throw new IllegalStateException(message + " (axiz.cqrs.authorization.unprotected=fail)");
    }
    LOG.warn("{}. Any authenticated caller can send them.", message);
  }
}
