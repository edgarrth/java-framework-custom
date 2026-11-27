package pe.axiz.kit.dynamodb;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Supplier;
import pe.axiz.core.crypto.EncryptionContext;
import pe.axiz.core.crypto.FieldEncryptor;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.enhanced.dynamodb.mapper.StaticAttribute;
import software.amazon.awssdk.enhanced.dynamodb.mapper.StaticTableSchema;

/**
 * Construye un esquema explícito del Enhanced Client con conversores inyectados y contextos únicos
 * por columna cifrada. No usa instanciación por anotaciones ni un registro de cifrado global del
 * proceso.
 */
public final class EncryptedTableSchema<T> {
  private final StaticTableSchema.Builder<T> builder;
  private final FieldEncryptor encryptor;
  private final Set<String> names = new HashSet<>();
  private final Set<String> contexts = new HashSet<>();

  public EncryptedTableSchema(Class<T> type, Supplier<T> constructor, FieldEncryptor encryptor) {
    this.builder =
        StaticTableSchema.builder(Objects.requireNonNull(type))
            .newItemSupplier(Objects.requireNonNull(constructor));
    this.encryptor = Objects.requireNonNull(encryptor);
  }

  /**
   * Agrega un atributo normal del SDK, incluidas las claves de partición y de ordenamiento y las
   * etiquetas de versión.
   */
  public EncryptedTableSchema<T> attribute(StaticAttribute<T, ?> attribute) {
    Objects.requireNonNull(attribute);
    requireName(attribute.name());
    builder.addAttribute(attribute);
    return this;
  }

  /**
   * Instala un conversor de String inyectado para esta columna. El contexto es explícito y único en
   * el esquema; esto impide la sustitución de columnas, no la sustitución entre filas de la misma
   * columna.
   */
  public EncryptedTableSchema<T> encryptedString(
      String name, String context, Function<T, String> getter, BiConsumer<T, String> setter) {
    EncryptionContext.require(context);
    Objects.requireNonNull(getter);
    Objects.requireNonNull(setter);
    if (contexts.contains(context))
      throw new IllegalArgumentException("Encrypted columns require unique contexts");
    requireName(name);
    contexts.add(context);
    var converter =
        new EncryptedAttributeConverter(encryptor) {
          @Override
          protected String context() {
            return context;
          }
        };
    builder.addAttribute(
        String.class,
        attribute ->
            attribute.name(name).getter(getter).setter(setter).attributeConverter(converter));
    return this;
  }

  public TableSchema<T> build() {
    return builder.build();
  }

  private void requireName(String name) {
    if (name == null || name.isBlank() || name.length() > 1024 || !names.add(name))
      throw new IllegalArgumentException("Attribute name must be bounded, nonblank and unique");
  }
}
