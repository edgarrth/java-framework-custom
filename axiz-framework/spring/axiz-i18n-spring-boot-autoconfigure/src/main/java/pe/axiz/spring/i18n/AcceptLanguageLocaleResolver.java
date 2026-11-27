package pe.axiz.spring.i18n;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Locale;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import pe.axiz.core.i18n.LocaleResolver;

/**
 * Resolvedor de locale de servlet guiado solo por {@code Accept-Language}; el locale no puede
 * cambiarse.
 */
public final class AcceptLanguageLocaleResolver
    implements org.springframework.web.servlet.LocaleResolver {

  private final LocaleResolver delegate;

  public AcceptLanguageLocaleResolver(LocaleResolver delegate) {
    this.delegate = Objects.requireNonNull(delegate);
  }

  @Override
  public Locale resolveLocale(HttpServletRequest request) {
    return delegate.resolve(request.getHeader(HttpHeaders.ACCEPT_LANGUAGE));
  }

  @Override
  public void setLocale(
      HttpServletRequest request, @Nullable HttpServletResponse response, @Nullable Locale locale) {
    throw new UnsupportedOperationException("The locale is taken from the Accept-Language header");
  }
}
