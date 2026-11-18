package pe.axiz.kit.jpa;

import jakarta.persistence.AttributeConverter;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import pe.axiz.core.crypto.EncryptionContext;
import pe.axiz.core.crypto.FieldEncryptor;

/**
 * Base converter for an explicitly declared field or shared purpose. Each concrete converter
 * overrides context() with entity.field or purpose:name. Values are decrypted into the attribute
 * without mutating managed entities. A row-specific AAD belongs in the persistence adapter, since
 * an AttributeConverter does not know the owning row.
 */
public abstract class EncryptedConverter implements AttributeConverter<String, String> {

  private final FieldEncryptor encryptor;

  /** The JPA provider must obtain converters through its dependency-injection integration. */
  public EncryptedConverter(FieldEncryptor encryptor) {
    this.encryptor = Objects.requireNonNull(encryptor);
  }

  protected abstract String context();

  /** Validated AAD; also used by the startup mapping validation. */
  public final String encryptionContext() {
    return EncryptionContext.require(context());
  }

  @Override
  public @Nullable String convertToDatabaseColumn(@Nullable String attribute) {
    return encryptor.encrypt(attribute, encryptionContext());
  }

  @Override
  public @Nullable String convertToEntityAttribute(@Nullable String dbData) {
    return encryptor.decrypt(dbData, encryptionContext());
  }
}
