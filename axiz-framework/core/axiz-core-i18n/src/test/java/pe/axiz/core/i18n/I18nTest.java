package pe.axiz.core.i18n;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import org.junit.jupiter.api.Test;

class I18nTest {

  private static final Locale ES = Locale.forLanguageTag("es");
  private static final Locale ES_PE = Locale.forLanguageTag("es-PE");

  private final BundleMessageResolver resolver =
      new BundleMessageResolver(List.of("test.svc", "axiz.i18n.messages"), Locale.ENGLISH);

  @Test
  void serviceBundleWinsAndFallsBackToFramework() {
    assertEquals("Service specific not found", resolver.get("error.AXZ-CORE-0404", Locale.ENGLISH));
    assertEquals("No se encontró el recurso solicitado.", resolver.get("error.AXZ-CORE-0404", ES));
    assertEquals(
        "La cabecera X-Channel falta o no es válida.",
        resolver.get("error.AXZ-CORE-0002", ES_PE, Map.of("header", "X-Channel")));
  }

  @Test
  void exactLocaleThenLanguageThenDefault() {
    assertEquals("Hola Ana, bienvenido", resolver.get("greeting", ES_PE, Map.of("name", "Ana")));
    assertEquals("Hello Ana", resolver.get("greeting", Locale.FRENCH, Map.of("name", "Ana")));
    assertEquals("missing.key", resolver.get("missing.key", ES));
    assertEquals(Optional.empty(), resolver.find("missing.key", ES, Map.of()));
  }

  @Test
  void formatterHandlesUnknownAndLiteralBraces() {
    assertEquals("a {x} b 1 {}", MessageFormatter.format("a {x} b {y} {}", Map.of("y", 1)));
    assertEquals("plain", MessageFormatter.format("plain", Map.of("y", 1)));
  }

  @Test
  void localeResolution() {
    LocaleResolver lr = new LocaleResolver(List.of(Locale.ENGLISH, ES, ES_PE), ES);
    assertEquals(ES_PE, lr.resolve("es-PE,es;q=0.9,en;q=0.8"));
    assertEquals(Locale.ENGLISH, lr.resolve("en-US,en;q=0.9"));
    assertEquals(ES, lr.resolve("fr-FR"));
    assertEquals(ES, lr.resolve("%%%invalid;;q=x"));
    assertEquals(ES, lr.resolve(null));
    assertTrue(lr.supported().contains(ES_PE));
  }

  @Test
  void compositeUsesFirstMatch() {
    MessageResolver first = (k, l, p) -> k.equals("a") ? Optional.of("first") : Optional.empty();
    CompositeMessageResolver composite = new CompositeMessageResolver(List.of(first, resolver));
    assertEquals("first", composite.get("a", Locale.ENGLISH));
    assertEquals("Hello Bo", composite.get("greeting", Locale.ENGLISH, Map.of("name", "Bo")));
  }

  @Test
  void englishIsServedWhenTheDefaultLocaleIsSpanish() {
    // los servicios corren con es-PE por defecto: una petición en inglés no debe caer en español
    BundleMessageResolver framework =
        new BundleMessageResolver(List.of("axiz.i18n.messages"), ES_PE);
    assertEquals("The request is not valid.", framework.get("error.AXZ-CORE-0001", Locale.ENGLISH));
    assertEquals(
        "The request is not valid.",
        framework.get("error.AXZ-CORE-0001", Locale.forLanguageTag("en-GB")));
    assertEquals("La solicitud no es válida.", framework.get("error.AXZ-CORE-0001", ES_PE));
    // un idioma sin bundle recibe el locale por defecto
    assertEquals("La solicitud no es válida.", framework.get("error.AXZ-CORE-0001", Locale.FRENCH));
  }

  @Test
  void frameworkBundlesHaveTheSameKeys() throws IOException {
    Properties root = load("axiz/i18n/messages.properties");
    assertEquals(root.keySet(), load("axiz/i18n/messages_en.properties").keySet());
    assertEquals(root.keySet(), load("axiz/i18n/messages_es.properties").keySet());
    assertEquals(root, load("axiz/i18n/messages_en.properties"), "root and English agree");
  }

  private static Properties load(String resource) throws IOException {
    Properties p = new Properties();
    try (InputStream in = I18nTest.class.getClassLoader().getResourceAsStream(resource)) {
      p.load(new InputStreamReader(in, StandardCharsets.UTF_8));
    }
    return p;
  }
}
