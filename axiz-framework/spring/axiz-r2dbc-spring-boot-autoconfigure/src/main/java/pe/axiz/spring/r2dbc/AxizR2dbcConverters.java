package pe.axiz.spring.r2dbc;

import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import org.springframework.core.convert.converter.Converter;
import org.springframework.data.convert.ReadingConverter;
import org.springframework.data.convert.WritingConverter;
import pe.axiz.core.crypto.EncryptionContext;
import pe.axiz.core.crypto.EncryptionRegistry;
import pe.axiz.core.crypto.FieldEncryptor;
import pe.axiz.core.money.Money;
import pe.axiz.kit.r2dbc.EncryptedText;

/**
 * Column converters of axiz types for Spring Data R2DBC: {@link EncryptedText} is stored as
 * AES-256-GCM ciphertext with an explicit field or shared purpose context and {@link Money} as its
 * text form ({@code PEN 150.00}). A service that declares its own {@code R2dbcCustomConversions}
 * includes {@link #all(String)} in it.
 */
public final class AxizR2dbcConverters {

  private AxizR2dbcConverters() {}

  /** Converters using the field encryptor registered by the crypto starter. */
  public static List<Object> all(String context) {
    return all(EncryptionRegistry::encryptor, context);
  }

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

  /** Encrypts on write. */
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

  /** Decrypts on read. */
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

  /** {@code Money} to {@code PEN 150.00}. */
  @WritingConverter
  public static final class MoneyWriter implements Converter<Money, String> {
    @Override
    public String convert(Money source) {
      return source.toString();
    }
  }

  /** {@code PEN 150.00} to {@code Money}. */
  @ReadingConverter
  public static final class MoneyReader implements Converter<String, Money> {
    @Override
    public Money convert(String source) {
      return Money.parse(source);
    }
  }
}
