package pe.axiz.core.i18n;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Chooses the response locale from an {@code Accept-Language} header among the supported ones (RFC
 * 4647 lookup with quality values). Invalid headers fall back to the default locale.
 */
public final class LocaleResolver {

  private final List<Locale> supported;
  private final Locale defaultLocale;

  public LocaleResolver(List<Locale> supported, Locale defaultLocale) {
    this.supported = List.copyOf(supported);
    this.defaultLocale = Objects.requireNonNull(defaultLocale);
  }

  public Locale resolve(@Nullable String acceptLanguage) {
    if (acceptLanguage == null || acceptLanguage.isBlank() || acceptLanguage.length() > 256) {
      return defaultLocale;
    }
    try {
      List<Locale.LanguageRange> ranges = Locale.LanguageRange.parse(acceptLanguage);
      Locale match = Locale.lookup(ranges, supported);
      if (match != null) {
        return match;
      }
      List<Locale> filtered = Locale.filter(ranges, supported);
      return filtered.isEmpty() ? defaultLocale : filtered.get(0);
    } catch (IllegalArgumentException e) {
      return defaultLocale;
    }
  }

  public Locale defaultLocale() {
    return defaultLocale;
  }

  public List<Locale> supported() {
    return supported;
  }
}
