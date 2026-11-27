package pe.axiz.kit.jpa;

import jakarta.persistence.AttributeConverter;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import pe.axiz.core.crypto.EncryptionContext;
import pe.axiz.core.crypto.FieldEncryptor;

/**
 * Conversor base para un campo declarado de forma explícita o un propósito compartido. Cada
 * conversor concreto sobrescribe context() con entity.field o purpose:name. Los valores se
 * descifran en el atributo sin mutar las entidades gestionadas. Un AAD específico de la fila
 * corresponde al adaptador de persistencia, ya que un AttributeConverter no conoce la fila
 * propietaria.
 */
public abstract class EncryptedConverter implements AttributeConverter<String, String> {

  private final FieldEncryptor encryptor;

  /**
   * El proveedor JPA debe obtener los conversores mediante su integración de inyección de
   * dependencias.
   */
  public EncryptedConverter(FieldEncryptor encryptor) {
    // Se rechazan las dependencias inválidas antes de que la instancia pueda llegar a ser
    // finalizable.
    Objects.requireNonNull(encryptor);
    super();
    this.encryptor = encryptor;
  }

  protected abstract String context();

  /** AAD validado; también lo usa la validación de mapeos al arrancar. */
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
