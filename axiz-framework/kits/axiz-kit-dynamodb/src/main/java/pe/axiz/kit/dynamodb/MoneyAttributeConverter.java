package pe.axiz.kit.dynamodb;

import pe.axiz.core.money.Money;
import software.amazon.awssdk.enhanced.dynamodb.AttributeConverter;
import software.amazon.awssdk.enhanced.dynamodb.AttributeValueType;
import software.amazon.awssdk.enhanced.dynamodb.EnhancedType;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

/**
 * Almacena {@link Money} en su forma de texto ({@code "PEN 150.00"}), nunca como un float binario.
 */
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
