package pe.axiz.core.persistence.jpa;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import org.jspecify.annotations.Nullable;
import pe.axiz.core.crypto.EncryptionRegistry;
import pe.axiz.core.crypto.FieldEncryptor;

/**
 * Encrypts a String attribute with AES-256-GCM: {@code @Convert(converter =
 * EncryptedConverter.class)}. Values are decrypted on read into the entity field; managed entities
 * are never mutated to decrypt. The context bound as AAD is the converter's purpose ({@code
 * axiz.field}); subclass it to bind a column-specific context.
 */
@Converter
public class EncryptedConverter implements AttributeConverter<String, String> {

  private final @Nullable FieldEncryptor injected;

  /** Used by JPA providers without dependency injection. */
  public EncryptedConverter() {
    this.injected = null;
  }

  /** Used when the provider obtains converters from the application context. */
  public EncryptedConverter(FieldEncryptor encryptor) {
    this.injected = encryptor;
  }

  protected String context() {
    return "axiz.field";
  }

  private FieldEncryptor encryptor() {
    return injected != null ? injected : EncryptionRegistry.encryptor();
  }

  @Override
  public @Nullable String convertToDatabaseColumn(@Nullable String attribute) {
    return encryptor().encrypt(attribute, context());
  }

  @Override
  public @Nullable String convertToEntityAttribute(@Nullable String dbData) {
    return encryptor().decrypt(dbData, context());
  }
}
