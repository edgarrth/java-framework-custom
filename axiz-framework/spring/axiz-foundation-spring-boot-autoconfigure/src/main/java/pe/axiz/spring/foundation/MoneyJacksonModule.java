package pe.axiz.spring.foundation;

import java.math.BigDecimal;
import pe.axiz.core.money.Money;
import tools.jackson.core.JsonGenerator;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.JsonNode;
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
      JsonNode node = ctxt.readTree(p);
      JsonNode amount = node.get("amount");
      JsonNode currency = node.get("currency");
      if (amount == null || currency == null) {
        return ctxt.reportInputMismatch(Money.class, "Money needs 'amount' and 'currency'");
      }
      BigDecimal value =
          amount.isNumber() ? amount.decimalValue() : new BigDecimal(amount.asString());
      // an amount from a client is never rounded: more decimals than the currency is an error
      return Money.ofExact(value, currency.asString());
    }
  }
}
