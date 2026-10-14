package pe.axiz.core.i18n;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/** Chains resolvers; the first one that knows the key wins (service before framework). */
public final class CompositeMessageResolver implements MessageResolver {

  private final List<MessageResolver> delegates;

  public CompositeMessageResolver(List<MessageResolver> delegates) {
    this.delegates = List.copyOf(delegates);
  }

  @Override
  public Optional<String> find(String key, Locale locale, Map<String, ?> parameters) {
    for (MessageResolver r : delegates) {
      Optional<String> m = r.find(key, locale, parameters);
      if (m.isPresent()) {
        return m;
      }
    }
    return Optional.empty();
  }
}
