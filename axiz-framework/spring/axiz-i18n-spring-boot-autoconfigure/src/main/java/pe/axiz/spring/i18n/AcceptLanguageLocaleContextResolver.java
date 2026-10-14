package pe.axiz.spring.i18n;

import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.springframework.context.i18n.LocaleContext;
import org.springframework.context.i18n.SimpleLocaleContext;
import org.springframework.http.HttpHeaders;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.i18n.LocaleContextResolver;
import pe.axiz.core.i18n.LocaleResolver;

/** Reactive locale resolver driven only by {@code Accept-Language}. */
public class AcceptLanguageLocaleContextResolver implements LocaleContextResolver {

  private final LocaleResolver delegate;

  public AcceptLanguageLocaleContextResolver(LocaleResolver delegate) {
    this.delegate = Objects.requireNonNull(delegate);
  }

  @Override
  public LocaleContext resolveLocaleContext(ServerWebExchange exchange) {
    return new SimpleLocaleContext(
        delegate.resolve(exchange.getRequest().getHeaders().getFirst(HttpHeaders.ACCEPT_LANGUAGE)));
  }

  @Override
  public void setLocaleContext(ServerWebExchange exchange, @Nullable LocaleContext localeContext) {
    throw new UnsupportedOperationException("The locale is taken from the Accept-Language header");
  }
}
