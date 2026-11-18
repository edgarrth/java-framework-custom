package pe.axiz.spring.sensitive;

import java.util.function.Function;
import pe.axiz.core.carddata.CardExpiry;
import pe.axiz.core.carddata.CardVerificationCode;
import pe.axiz.core.carddata.Pan;
import pe.axiz.core.carddata.Pin;
import pe.axiz.core.carddata.TruncatedPan;
import pe.axiz.core.errors.BusinessException;
import tools.jackson.core.JsonGenerator;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ValueSerializer;
import tools.jackson.databind.deser.std.StdDeserializer;
import tools.jackson.databind.module.SimpleModule;

/** Card types are masked by default; a revealing module belongs to one explicit outbound client. */
public final class CardDataJacksonModule extends SimpleModule {
  private static final long serialVersionUID = 1L;

  private CardDataJacksonModule(boolean reveal) {
    super(reveal ? "axiz-carddata-outbound" : "axiz-carddata");
    addSerializer(Pan.class, text(reveal ? Pan::reveal : Pan::masked));
    addSerializer(TruncatedPan.class, text(TruncatedPan::masked));
    addSerializer(CardExpiry.class, text(CardExpiry::formatted));
    addSerializer(
        CardVerificationCode.class,
        new ValueSerializer<>() {
          @Override
          public void serialize(
              CardVerificationCode value, JsonGenerator gen, SerializationContext context) {
            if (reveal)
              value.use(
                  chars -> {
                    gen.writeString(chars, 0, chars.length);
                    return true;
                  });
            else gen.writeString(value.masked());
          }
        });
    addSerializer(
        Pin.class,
        new ValueSerializer<>() {
          @Override
          public void serialize(Pin value, JsonGenerator gen, SerializationContext context) {
            if (reveal)
              value.use(
                  chars -> {
                    gen.writeString(chars, 0, chars.length);
                    return true;
                  });
            else gen.writeString(value.masked());
          }
        });
    addDeserializer(Pan.class, new TextReader<>(Pan.class, Pan::parse));
    addDeserializer(
        CardVerificationCode.class,
        new TextReader<>(CardVerificationCode.class, CardVerificationCode::parse));
    addDeserializer(Pin.class, new TextReader<>(Pin.class, Pin::parse));
    addDeserializer(CardExpiry.class, new TextReader<>(CardExpiry.class, CardExpiry::parse));
  }

  public static CardDataJacksonModule masking() {
    return new CardDataJacksonModule(false);
  }

  public static CardDataJacksonModule revealing() {
    return new CardDataJacksonModule(true);
  }

  private static <T> ValueSerializer<T> text(Function<T, String> render) {
    return new ValueSerializer<>() {
      @Override
      public void serialize(T value, JsonGenerator gen, SerializationContext context) {
        gen.writeString(render.apply(value));
      }
    };
  }

  private static final class TextReader<T> extends StdDeserializer<T> {
    private final Class<T> type;
    private final Function<String, T> parser;

    TextReader(Class<T> type, Function<String, T> parser) {
      super(type);
      this.type = type;
      this.parser = parser;
    }

    @Override
    public T deserialize(JsonParser input, DeserializationContext context) {
      if (input.currentToken() != JsonToken.VALUE_STRING)
        return context.reportInputMismatch(type, "Card data requires text");
      try {
        return parser.apply(input.getString());
      } catch (IllegalArgumentException | BusinessException invalid) {
        return context.reportInputMismatch(type, "Invalid card data");
      }
    }
  }
}
