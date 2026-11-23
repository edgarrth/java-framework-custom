package pe.axiz.core.identifiers;

import java.util.List;

/**
 * Se descubre con {@link java.util.ServiceLoader} para registrar paquetes de tipos de
 * identificador.
 */
public interface IdentifierTypeProvider {

  List<IdentifierType> types();
}
