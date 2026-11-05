package pe.axiz.kit.jpa;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import org.jspecify.annotations.Nullable;
import pe.axiz.core.crypto.EncryptionRegistry;
import pe.axiz.core.crypto.FieldEncryptor;

/** Read-only migration access to historical axiz.field ciphertext. Reencrypt before new writes. */
@Deprecated(forRemoval = true)
@Converter
public final class LegacyEncryptedConverter implements AttributeConverter<String, String> {
  private final @Nullable FieldEncryptor encryptor;

  public LegacyEncryptedConverter() {
    encryptor = null;
  }

  public LegacyEncryptedConverter(FieldEncryptor encryptor) {
    this.encryptor = encryptor;
  }

  @Override
  public @Nullable String convertToEntityAttribute(@Nullable String value) {
    return (encryptor != null ? encryptor : EncryptionRegistry.encryptor())
        .decrypt(value, "axiz.field");
  }

  @Override
  public String convertToDatabaseColumn(@Nullable String value) {
    throw new UnsupportedOperationException(
        "LegacyEncryptedConverter only reads; migrate with FieldEncryptor.reencrypt");
  }
}
