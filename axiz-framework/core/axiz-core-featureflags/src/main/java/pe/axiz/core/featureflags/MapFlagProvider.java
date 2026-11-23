package pe.axiz.core.featureflags;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Flags desde la configuración (enlazados desde el ConfigMap del servicio). */
public final class MapFlagProvider implements FlagProvider {

  private final Map<String, FlagDefinition> flags;

  public MapFlagProvider(Collection<FlagDefinition> flags) {
    this.flags =
        flags.stream()
            .collect(Collectors.toUnmodifiableMap(FlagDefinition::name, Function.identity()));
  }

  @Override
  public Optional<FlagDefinition> find(String name) {
    return Optional.ofNullable(flags.get(name));
  }
}
