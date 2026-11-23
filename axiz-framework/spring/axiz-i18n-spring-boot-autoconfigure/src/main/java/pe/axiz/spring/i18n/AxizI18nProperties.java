package pe.axiz.spring.i18n;

import java.util.List;
import java.util.Locale;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Configuración de localización ({@code axiz.i18n.*}).
 *
 * @param defaultLocale locale usado cuando el cliente no envía un {@code Accept-Language}
 *     utilizable
 * @param supportedLocales locales en los que responde el servicio
 * @param basenames bundles del servicio (classpath, propiedades UTF-8); prevalecen sobre los
 *     mensajes del framework
 */
@ConfigurationProperties("axiz.i18n")
public record AxizI18nProperties(
    @DefaultValue("es-PE") Locale defaultLocale,
    @DefaultValue({"es-PE", "es", "en"}) List<Locale> supportedLocales,
    @DefaultValue("messages") List<String> basenames) {
  public AxizI18nProperties {
    basenames = List.copyOf(basenames);
    supportedLocales = List.copyOf(supportedLocales);
  }
}
