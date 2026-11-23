package pe.axiz.kit.dynamodb;

import java.util.Objects;
import pe.axiz.core.crypto.EncryptionContext;
import pe.axiz.core.crypto.FieldEncryptor;
import software.amazon.awssdk.enhanced.dynamodb.AttributeConverter;
import software.amazon.awssdk.enhanced.dynamodb.AttributeValueType;
import software.amazon.awssdk.enhanced.dynamodb.EnhancedType;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

/**
 * Conversor por campo inyectado. La instancia se instala con un esquema de tabla explícito del
 * Enhanced Client.
 */
public abstract class EncryptedAttributeConverter implements AttributeConverter<String> {

  private final FieldEncryptor encryptor;

  protected EncryptedAttributeConverter(FieldEncryptor encryptor) {
    // Se valida antes de construir el Object, para que las subclases inválidas nunca lleguen a ser
    // finalizables.
    Objects.requireNonNull(encryptor);
    super();
    this.encryptor = encryptor;
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
      // el atributo no lo escribió este conversor (otro tipo): nunca se devuelve null
      throw new IllegalArgumentException(
          "Encrypted attribute expected as a string, found " + input.type());
    }
    // decrypt solo responde null para una entrada null
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
