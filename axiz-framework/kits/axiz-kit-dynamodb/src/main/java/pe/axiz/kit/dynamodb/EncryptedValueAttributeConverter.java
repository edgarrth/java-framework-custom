package pe.axiz.kit.dynamodb;

import pe.axiz.core.crypto.EncryptedValue;
import software.amazon.awssdk.enhanced.dynamodb.AttributeConverter;
import software.amazon.awssdk.enhanced.dynamodb.AttributeValueType;
import software.amazon.awssdk.enhanced.dynamodb.EnhancedType;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

/**
 * Storage codec for values encrypted before persistence, including row-bound field protection. This
 * codec preserves the envelope; it neither encrypts nor authenticates it. The consumer decrypts
 * with the expected context and record binding. Plaintext and non-string attributes are rejected
 * instead of being accepted as legacy values.
 */
public final class EncryptedValueAttributeConverter implements AttributeConverter<EncryptedValue> {
  @Override
  public AttributeValue transformFrom(EncryptedValue input) {
    return AttributeValue.fromS(input.encoded());
  }

  @Override
  public EncryptedValue transformTo(AttributeValue input) {
    return EncryptedValue.parse(input.s());
  }

  @Override
  public EnhancedType<EncryptedValue> type() {
    return EnhancedType.of(EncryptedValue.class);
  }

  @Override
  public AttributeValueType attributeValueType() {
    return AttributeValueType.S;
  }
}
