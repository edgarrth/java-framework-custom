package pe.axiz.spring.i18n;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.mock.web.MockHttpServletRequest;
import pe.axiz.core.errors.ErrorMessageResolver;
import pe.axiz.core.i18n.LocaleResolver;
import pe.axiz.core.i18n.MessageResolver;

class AxizI18nAutoConfigurationTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(AxizI18nAutoConfiguration.class))
          .withPropertyValues("axiz.i18n.basenames=i18n/svc");

  @Test
  void serviceBundleWinsOverFrameworkBundle() {
    runner.run(
        ctx -> {
          ErrorMessageResolver errors = ctx.getBean(ErrorMessageResolver.class);
          assertEquals(
              "Nada por aquí.",
              errors.resolve("error.AXZ-CORE-0404", Locale.of("es", "PE"), Map.of()).orElseThrow());
          assertEquals(
              "The request is not valid.",
              errors.resolve("error.AXZ-CORE-0001", Locale.ENGLISH, Map.of()).orElseThrow());
          assertEquals(
              "Hola Ana",
              ctx.getBean(MessageResolver.class)
                  .get("greeting", Locale.of("es"), Map.of("name", "Ana")));
        });
  }

  @Test
  void acceptLanguageChoosesSupportedLocale() {
    runner.run(
        ctx -> {
          LocaleResolver r = ctx.getBean(LocaleResolver.class);
          assertEquals(Locale.ENGLISH, r.resolve("en-GB;q=0.9, fr;q=0.8"));
          assertEquals(Locale.forLanguageTag("es-PE"), r.resolve("de"));
        });
  }

  @Test
  void servletLocaleResolverIsRegistered() {
    new WebApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(AxizI18nAutoConfiguration.class))
        .run(
            ctx -> {
              Object bean = ctx.getBean("localeResolver");
              AcceptLanguageLocaleResolver resolver =
                  assertInstanceOf(AcceptLanguageLocaleResolver.class, bean);
              MockHttpServletRequest request = new MockHttpServletRequest();
              request.addHeader("Accept-Language", "es");
              assertEquals(Locale.of("es"), resolver.resolveLocale(request));
            });
  }
}
