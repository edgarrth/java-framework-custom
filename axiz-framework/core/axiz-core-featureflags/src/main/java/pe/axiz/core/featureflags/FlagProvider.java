package pe.axiz.core.featureflags;

import java.util.Optional;

/** Fuente de definiciones de flags (propiedades enlazadas a un ConfigMap, Parameter Store). */
@FunctionalInterface
public interface FlagProvider {

  Optional<FlagDefinition> find(String name);
}
