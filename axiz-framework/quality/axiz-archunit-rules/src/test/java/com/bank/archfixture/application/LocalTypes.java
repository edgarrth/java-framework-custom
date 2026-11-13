// Consumer fixture intentionally uses a non-Axiz namespace to verify package ownership.
// CHECKSTYLE:OFF: PackageName
package com.bank.archfixture.application;

// CHECKSTYLE:ON: PackageName

import java.util.List;

public final class LocalTypes {
  private LocalTypes() {}

  public record Input(String value) {}

  public record Values(List<Input> items) {}
}
