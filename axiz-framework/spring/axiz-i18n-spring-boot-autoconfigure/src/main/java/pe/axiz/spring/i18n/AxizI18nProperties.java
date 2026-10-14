package pe.axiz.spring.i18n;

import java.util.List;
import java.util.Locale;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Localization settings ({@code axiz.i18n.*}).
 *
 * @param defaultLocale locale used when the client sends no usable {@code Accept-Language}
 * @param supportedLocales locales the service answers in
 * @param basenames service bundles (classpath, UTF-8 properties); they win over framework messages
 */
@ConfigurationProperties("axiz.i18n")
public record AxizI18nProperties(
    @DefaultValue("es-PE") Locale defaultLocale,
    @DefaultValue({"es-PE", "es", "en"}) List<Locale> supportedLocales,
    @DefaultValue("messages") List<String> basenames) {}
