// Consumer fixture intentionally uses a non-Axiz namespace to verify package ownership.
// CHECKSTYLE:OFF: PackageName
package com.bank.archfixture.application;

// CHECKSTYLE:ON: PackageName

import com.bank.archfixturex.ForeignType;

public record ForeignLeak(ForeignType value) {}
