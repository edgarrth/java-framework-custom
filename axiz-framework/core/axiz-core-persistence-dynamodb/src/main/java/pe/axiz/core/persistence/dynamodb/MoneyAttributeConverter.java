package pe.axiz.core.persistence.dynamodb;

import pe.axiz.core.money.Money;
import software.amazon.awssdk.enhanced.dynamodb.AttributeConverter;
import software.amazon.awssdk.enhanced.dynamodb.AttributeValueType;
import software.amazon.awssdk.enhanced.dynamodb.EnhancedType;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

/** Stores {@link Money} as its text form ({@code "PEN 150.00"}), never as a binary float. */
public final class MoneyAttributeConverter implements AttributeConverter<Money> {

  @Override
  public AttributeValue transformFrom(Money input) {
    return AttributeValue.builder().s(input.toString()).build();
  }

  @Override
  public Money transformTo(AttributeValue input) {
    return Money.parse(input.s());
  }

  @Override
  public EnhancedType<Money> type() {
    return EnhancedType.of(Money.class);
  }

  @Override
  public AttributeValueType attributeValueType() {
    return AttributeValueType.S;
  }
}
