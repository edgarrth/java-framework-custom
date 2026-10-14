package pe.axiz.core.domain;

import pe.axiz.core.errors.ErrorCategory;
import pe.axiz.core.errors.ErrorDefinition;

/** Errors raised by the domain building blocks. */
public final class DomainErrors {

  public static final ErrorDefinition INVALID_VALUE =
      ErrorDefinition.of("AXZ-DOMAIN-0001", ErrorCategory.BAD_REQUEST, "Invalid value");
  public static final ErrorDefinition RULE_VIOLATED =
      ErrorDefinition.of("AXZ-DOMAIN-0002", ErrorCategory.UNPROCESSABLE, "Business rule violated");

  private DomainErrors() {}
}
