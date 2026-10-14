package pe.axiz.core.i18n;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.MissingResourceException;
import java.util.Objects;
import java.util.Optional;
import java.util.ResourceBundle;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Message resolver over several {@link ResourceBundle} base names (UTF-8 properties).
 *
 * <p>Lookup order for each bundle: requested locale, its language, the configured default locale,
 * then the root bundle. The JVM default locale is never consulted, so results do not depend on the
 * machine where the service runs. Bundles listed first win.
 */
public final class BundleMessageResolver implements MessageResolver {

  private static final ResourceBundle.Control CONTROL =
      ResourceBundle.Control.getNoFallbackControl(ResourceBundle.Control.FORMAT_PROPERTIES);

  private final List<String> baseNames;
  private final Locale defaultLocale;
  private final ClassLoader classLoader;

  public BundleMessageResolver(
      List<String> baseNames, Locale defaultLocale, ClassLoader classLoader) {
    this.baseNames = List.copyOf(baseNames);
    this.defaultLocale = Objects.requireNonNull(defaultLocale);
    this.classLoader = Objects.requireNonNull(classLoader);
  }

  public BundleMessageResolver(List<String> baseNames, Locale defaultLocale) {
    this(baseNames, defaultLocale, BundleMessageResolver.class.getClassLoader());
  }

  @Override
  public Optional<String> find(String key, Locale locale, Map<String, ?> parameters) {
    for (Locale candidate : candidates(locale)) {
      for (String baseName : baseNames) {
        ResourceBundle bundle = bundle(baseName, candidate);
        if (bundle != null && bundle.getLocale().equals(candidate) && bundle.containsKey(key)) {
          return Optional.of(MessageFormatter.format(bundle.getString(key), parameters));
        }
      }
    }
    return Optional.empty();
  }

  private List<Locale> candidates(Locale locale) {
    Set<Locale> out = new LinkedHashSet<>();
    out.add(locale);
    if (!locale.getLanguage().isEmpty()) {
      out.add(Locale.of(locale.getLanguage()));
    }
    out.add(defaultLocale);
    if (!defaultLocale.getLanguage().isEmpty()) {
      out.add(Locale.of(defaultLocale.getLanguage()));
    }
    out.add(Locale.ROOT);
    return new ArrayList<>(out);
  }

  private @Nullable ResourceBundle bundle(String baseName, Locale locale) {
    try {
      return ResourceBundle.getBundle(baseName, locale, classLoader, CONTROL);
    } catch (MissingResourceException e) {
      return null;
    }
  }
}
