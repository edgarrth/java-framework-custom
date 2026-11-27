package pe.axiz.spring.sensitive;

import pe.axiz.core.sensitive.SelfMasking;
import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ValueSerializer;
import tools.jackson.databind.module.SimpleModule;

/**
 * Los tipos intrínsecamente sensibles nunca exponen sus detalles internos a través de un mapper
 * genérico.
 */
public final class SensitiveValueJacksonModule extends SimpleModule {
  private static final long serialVersionUID = 1L;

  public SensitiveValueJacksonModule() {
    super("axiz-sensitive-values");
    addSerializer(
        SelfMasking.class,
        new ValueSerializer<>() {
          @Override
          public void serialize(
              SelfMasking value, JsonGenerator gen, SerializationContext context) {
            gen.writeString(value.masked());
          }
        });
  }
}
