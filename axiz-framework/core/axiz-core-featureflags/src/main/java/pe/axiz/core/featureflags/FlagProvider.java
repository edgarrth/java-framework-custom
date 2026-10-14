package pe.axiz.core.featureflags;

import java.util.Optional;

/** Source of flag definitions (ConfigMap-bound properties, Parameter Store). */
@FunctionalInterface
public interface FlagProvider {

  Optional<FlagDefinition> find(String name);
}
