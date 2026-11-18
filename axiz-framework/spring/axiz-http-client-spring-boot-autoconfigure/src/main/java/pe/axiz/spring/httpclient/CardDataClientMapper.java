package pe.axiz.spring.httpclient;

import org.springframework.util.ClassUtils;
import pe.axiz.spring.sensitive.CardDataJacksonModule;
import pe.axiz.spring.sensitive.SensitiveValueJacksonModule;
import tools.jackson.databind.json.JsonMapper;

/** Creates immutable client-local mappers; never changes the application mapper. */
final class CardDataClientMapper {
  private CardDataClientMapper() {}

  static JsonMapper forClient(JsonMapper base, boolean reveal) {
    var builder = base.rebuild().addModule(new SensitiveValueJacksonModule());
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
