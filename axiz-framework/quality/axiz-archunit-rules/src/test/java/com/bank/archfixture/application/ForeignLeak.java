// El fixture de consumidor usa de forma intencional un espacio de nombres ajeno a Axiz para
// verificar la propiedad de los paquetes.
// CHECKSTYLE:OFF: PackageName
package com.bank.archfixture.application;

// CHECKSTYLE:ON: PackageName

import com.bank.archfixturex.ForeignType;

public record ForeignLeak(ForeignType value) {}
