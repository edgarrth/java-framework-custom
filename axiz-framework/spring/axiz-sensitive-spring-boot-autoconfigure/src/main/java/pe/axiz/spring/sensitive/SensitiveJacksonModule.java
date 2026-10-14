package pe.axiz.spring.sensitive;

import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import pe.axiz.core.sensitive.Mask;
import pe.axiz.core.sensitive.MaskingPolicy;
import pe.axiz.core.sensitive.OutputChannel;
import pe.axiz.core.sensitive.Sensitive;
import pe.axiz.core.sensitive.SensitiveField;
import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.BeanDescription;
import tools.jackson.databind.SerializationConfig;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ValueSerializer;
import tools.jackson.databind.module.SimpleModule;
import tools.jackson.databind.ser.BeanPropertyWriter;
import tools.jackson.databind.ser.ValueSerializerModifier;

/**
 * Masks properties annotated with {@link Sensitive} or {@link Mask} in JSON responses, following
 * the {@link OutputChannel#RESPONSE} policy. Values below the masking threshold keep their JSON
 * type; masked values are written as strings.
 */
public class SensitiveJacksonModule extends SimpleModule {

  private static final long serialVersionUID = 1L;

  public SensitiveJacksonModule(MaskingPolicy policy) {
    super("axiz-sensitive");
    Objects.requireNonNull(policy);
    setSerializerModifier(
        new ValueSerializerModifier() {
          private static final long serialVersionUID = 1L;

          @Override
          public List<BeanPropertyWriter> changeProperties(
              SerializationConfig config,
              BeanDescription.Supplier beanDesc,
              List<BeanPropertyWriter> properties) {
            for (BeanPropertyWriter w : properties) {
              SensitiveField field = field(w);
              if (field != null && !w.hasSerializer()) {
                w.assignSerializer(new MaskingSerializer(policy, field));
              }
            }
            return properties;
          }
        });
  }

  private static @Nullable SensitiveField field(BeanPropertyWriter w) {
    Sensitive s = w.getAnnotation(Sensitive.class);
    if (s != null) {
      return SensitiveField.of(w.getName(), s);
    }
    Mask m = w.getAnnotation(Mask.class);
    return m != null ? SensitiveField.of(w.getName(), m) : null;
  }

  static final class MaskingSerializer extends ValueSerializer<Object> {

    private final MaskingPolicy policy;
    private final SensitiveField field;

    MaskingSerializer(MaskingPolicy policy, SensitiveField field) {
      this.policy = policy;
      this.field = field;
    }

    @Override
    public void serialize(Object value, JsonGenerator gen, SerializationContext ctxt) {
      String raw = String.valueOf(value);
      String masked = policy.apply(raw, field.level(), field.mask(), OutputChannel.RESPONSE);
      if (masked == null) {
        gen.writeNull();
      } else if (masked.equals(raw)) {
        ctxt.writeValue(gen, value);
      } else {
        gen.writeString(masked);
      }
    }
  }
}
