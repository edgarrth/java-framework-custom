package pe.axiz.spring.httpclient;

import org.springframework.util.ClassUtils;
import pe.axiz.spring.sensitive.CardDataJacksonModule;
import pe.axiz.spring.sensitive.SensitiveValueJacksonModule;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Crea mappers inmutables locales al cliente; nunca modifica el mapper de la aplicación. Un cliente
 * lee las respuestas de terceros de forma tolerante (un proveedor puede añadir campos), incluso
 * cuando el servicio rechaza propiedades no declaradas en sus propias solicitudes.
 */
final class CardDataClientMapper {
  private CardDataClientMapper() {}

  static JsonMapper forClient(JsonMapper base, boolean reveal) {
    var builder =
        base.rebuild()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .addModule(new SensitiveValueJacksonModule());
    if (ClassUtils.isPresent(
        "pe.axiz.core.carddata.Pan", CardDataClientMapper.class.getClassLoader())) {
      builder.addModule(
          reveal ? CardDataJacksonModule.revealing() : CardDataJacksonModule.masking());
    } else if (reveal) {
      throw new IllegalStateException("reveal-card-data requires axiz-core-carddata");
    }
    return builder.build();
  }
}
