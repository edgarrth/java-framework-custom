package pe.axiz.kit.dynamodb;

import java.util.Objects;
import pe.axiz.core.crypto.EncryptionContext;
import pe.axiz.core.crypto.FieldEncryptor;
import software.amazon.awssdk.enhanced.dynamodb.AttributeConverter;
import software.amazon.awssdk.enhanced.dynamodb.AttributeValueType;
import software.amazon.awssdk.enhanced.dynamodb.EnhancedType;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

/**
 * Injected per-field converter. Install the instance with an explicit Enhanced Client table schema.
 */
public abstract class EncryptedAttributeConverter implements AttributeConverter<String> {

  private final FieldEncryptor encryptor;

  protected EncryptedAttributeConverter(FieldEncryptor encryptor) {
    this.encryptor = Objects.requireNonNull(encryptor);
  }

  protected abstract String context();

  @Override
  public AttributeValue transformFrom(String input) {
    return AttributeValue.builder()
        .s(encryptor.encrypt(input, EncryptionContext.require(context())))
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
    return Objects.requireNonNull(
        encryptor.decrypt(encrypted, EncryptionContext.require(context())));
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
