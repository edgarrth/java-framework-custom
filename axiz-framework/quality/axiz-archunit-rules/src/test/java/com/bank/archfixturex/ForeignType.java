// El fixture de consumidor usa de forma intencional un espacio de nombres ajeno a Axiz para
// verificar la propiedad de los paquetes.
// CHECKSTYLE:OFF: PackageName
package com.bank.archfixturex;

// CHECKSTYLE:ON: PackageName

public record ForeignType(String value) {}
