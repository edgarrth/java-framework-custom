package pe.axiz.core.persistence.dynamodb;

import java.util.Objects;
import pe.axiz.core.crypto.EncryptionRegistry;
import software.amazon.awssdk.enhanced.dynamodb.AttributeConverter;
import software.amazon.awssdk.enhanced.dynamodb.AttributeValueType;
import software.amazon.awssdk.enhanced.dynamodb.EnhancedType;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

/**
 * Encrypts String attributes with the registered field encryptor:
 * {@code @DynamoDbConvertedBy(EncryptedAttributeConverter.class)}.
 */
public class EncryptedAttributeConverter implements AttributeConverter<String> {

  protected String context() {
    return "axiz.dynamodb.field";
  }

  @Override
  public AttributeValue transformFrom(String input) {
    return AttributeValue.builder()
        .s(EncryptionRegistry.encryptor().encrypt(input, context()))
        .build();
  }

  @Override
  public String transformTo(AttributeValue input) {
    String encrypted = input.s();
    if (encrypted == null) {
      // the attribute was not written by this converter (another type): never return a null
      throw new IllegalArgumentException(
          "Encrypted attribute expected as a string, found " + input.type());
    }
    // decrypt only answers null for a null input
    return Objects.requireNonNull(EncryptionRegistry.encryptor().decrypt(encrypted, context()));
  }

  @Override
  public EnhancedType<String> type() {
    return EnhancedType.of(String.class);
  }

  @Override
  public AttributeValueType attributeValueType() {
    return AttributeValueType.S;
  }
}
