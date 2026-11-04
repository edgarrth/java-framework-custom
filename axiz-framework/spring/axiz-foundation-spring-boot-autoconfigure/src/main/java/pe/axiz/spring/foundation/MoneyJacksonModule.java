package pe.axiz.spring.foundation;

import java.math.BigDecimal;
import org.jspecify.annotations.Nullable;
import pe.axiz.core.money.Money;
import tools.jackson.core.JsonGenerator;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.deser.std.StdDeserializer;
import tools.jackson.databind.module.SimpleModule;
import tools.jackson.databind.ser.std.StdSerializer;

/**
 * Jackson module for {@link Money}: the amount is written as a string so clients never parse it as
 * a binary floating point number. Reading accepts the object form or {@code "PEN 150.00"}.
 */
public final class MoneyJacksonModule extends SimpleModule {

  private static final long serialVersionUID = 1L;

  public MoneyJacksonModule() {
    super("axiz-money");
    addSerializer(Money.class, new MoneySerializer());
    addDeserializer(Money.class, new MoneyDeserializer());
  }

  static final class MoneySerializer extends StdSerializer<Money> {
    MoneySerializer() {
      super(Money.class);
    }

    @Override
    public void serialize(Money value, JsonGenerator gen, SerializationContext ctxt) {
      gen.writeStartObject();
      gen.writeStringProperty("amount", value.amountText());
      gen.writeStringProperty("currency", value.currencyCode());
      gen.writeEndObject();
    }
  }

  static final class MoneyDeserializer extends StdDeserializer<Money> {
    MoneyDeserializer() {
      super(Money.class);
    }

    @Override
    public Money deserialize(JsonParser p, DeserializationContext ctxt) {
      if (p.currentToken() == JsonToken.VALUE_STRING) {
        return Money.parse(p.getString());
      }
      if (p.currentToken() != JsonToken.START_OBJECT) {
        return ctxt.reportInputMismatch(Money.class, "Money must be an object or text");
      }
      @Nullable BigDecimal amount = null;
      @Nullable String currency = null;
      while (p.nextToken() == JsonToken.PROPERTY_NAME) {
        String field = p.currentName();
        JsonToken token = p.nextToken();
        if ("amount".equals(field)) {
          if (amount != null) {
            return ctxt.reportInputMismatch(Money.class, "Duplicate Money amount");
          }
          if (token == JsonToken.VALUE_NUMBER_INT
              || token == JsonToken.VALUE_NUMBER_FLOAT
              || token == JsonToken.VALUE_STRING) {
            // Read the lexeme, not getDecimalValue(): conversion must follow the length guard.
            String literal = p.getString();
            if (literal.length() > Money.MAX_TEXT_LENGTH) {
              return ctxt.reportInputMismatch(Money.class, "Money amount is too long");
            }
            try {
              amount = new BigDecimal(literal.strip());
            } catch (NumberFormatException e) {
              return ctxt.reportInputMismatch(Money.class, "Invalid Money amount");
            }
          } else {
            return ctxt.reportInputMismatch(Money.class, "Money amount must be a number or text");
          }
        } else if ("currency".equals(field)) {
          if (currency != null || token != JsonToken.VALUE_STRING) {
            return ctxt.reportInputMismatch(Money.class, "Money needs one textual currency");
          }
          currency = p.getString();
        } else {
          p.skipChildren();
        }
      }
      if (p.currentToken() != JsonToken.END_OBJECT || amount == null || currency == null) {
        return ctxt.reportInputMismatch(Money.class, "Money needs 'amount' and 'currency'");
      }
      return Money.ofExact(amount, currency);
    }
  }
}
