package pe.axiz.spring.r2dbc;

import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import org.springframework.core.convert.converter.Converter;
import org.springframework.data.convert.ReadingConverter;
import org.springframework.data.convert.WritingConverter;
import pe.axiz.core.crypto.EncryptionContext;
import pe.axiz.core.crypto.FieldEncryptor;
import pe.axiz.core.money.Money;
import pe.axiz.kit.r2dbc.EncryptedText;

/**
 * Converters de columnas de tipos de axiz para Spring Data R2DBC: {@link EncryptedText} se almacena
 * como texto cifrado AES-256-GCM con un contexto explícito de campo o de propósito compartido, y
 * {@link Money} en su forma de texto ({@code PEN 150.00}). Un servicio que declara sus propias
 * {@code R2dbcCustomConversions} incluye en ellas {@link #all(Supplier, String)}.
 */
public final class AxizR2dbcConverters {

  private AxizR2dbcConverters() {}

  public static List<Object> all(Supplier<FieldEncryptor> encryptor, String context) {
    EncryptionContext.require(context);
    return List.of(
        new EncryptedTextWriter(encryptor, context),
        new EncryptedTextReader(encryptor, context),
        new MoneyWriter(),
        new MoneyReader());
  }

  public static List<Object> money() {
    return List.of(new MoneyWriter(), new MoneyReader());
  }

  /** Cifra al escribir. */
  @WritingConverter
  public static final class EncryptedTextWriter implements Converter<EncryptedText, String> {

    private final Supplier<FieldEncryptor> encryptor;
    private final String context;

    EncryptedTextWriter(Supplier<FieldEncryptor> encryptor, String context) {
      this.encryptor = Objects.requireNonNull(encryptor);
      this.context = EncryptionContext.require(context);
    }

    @Override
    public String convert(EncryptedText source) {
      return Objects.requireNonNull(encryptor.get().encrypt(source.value(), context));
    }
  }

  /** Descifra al leer. */
  @ReadingConverter
  public static final class EncryptedTextReader implements Converter<String, EncryptedText> {

    private final Supplier<FieldEncryptor> encryptor;
    private final String context;

    EncryptedTextReader(Supplier<FieldEncryptor> encryptor, String context) {
      this.encryptor = Objects.requireNonNull(encryptor);
      this.context = EncryptionContext.require(context);
    }

    @Override
    public EncryptedText convert(String source) {
      return EncryptedText.of(Objects.requireNonNull(encryptor.get().decrypt(source, context)));
    }
  }

  /** {@code Money} a {@code PEN 150.00}. */
  @WritingConverter
  public static final class MoneyWriter implements Converter<Money, String> {
    @Override
    public String convert(Money source) {
      return source.toString();
    }
  }

  /** {@code PEN 150.00} a {@code Money}. */
  @ReadingConverter
  public static final class MoneyReader implements Converter<String, Money> {
    @Override
    public Money convert(String source) {
      return Money.parse(source);
    }
  }
}
