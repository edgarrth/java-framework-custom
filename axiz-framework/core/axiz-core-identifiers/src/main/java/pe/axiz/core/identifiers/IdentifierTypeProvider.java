package pe.axiz.core.identifiers;

import java.util.List;

/** Discovered with {@link java.util.ServiceLoader} to register packs of identifier types. */
public interface IdentifierTypeProvider {

  List<IdentifierType> types();
}
