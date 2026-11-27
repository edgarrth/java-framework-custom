package pe.axiz.kit.dynamodb;

import pe.axiz.core.crypto.EncryptedValue;
import software.amazon.awssdk.enhanced.dynamodb.AttributeConverter;
import software.amazon.awssdk.enhanced.dynamodb.AttributeValueType;
import software.amazon.awssdk.enhanced.dynamodb.EnhancedType;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

/**
 * Codec de almacenamiento para valores cifrados antes de la persistencia, incluida la protección de
 * campos ligada a la fila. Este codec preserva el envelope; no lo cifra ni lo autentica. El
 * consumidor descifra con el contexto y el enlace de registro esperados. Los atributos en texto
 * plano y los que no son cadenas se rechazan en lugar de aceptarse como valores sin cifrar.
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
