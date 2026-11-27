package pe.axiz.quality.archunit.sample.sensitive.model;

import jakarta.persistence.Access;
import jakarta.persistence.AccessType;
import jakarta.persistence.Convert;
import jakarta.persistence.Converter;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import java.util.List;
import java.util.Optional;
import pe.axiz.core.carddata.CardVerificationCode;
import pe.axiz.core.carddata.Pan;
import pe.axiz.core.carddata.TruncatedPan;
import pe.axiz.core.crypto.EncryptedValue;
import pe.axiz.core.crypto.FieldEncryptor;
import pe.axiz.core.crypto.KeyedHash;
import pe.axiz.core.saga.SagaDefinition;
import pe.axiz.core.sensitive.Sensitive;
import pe.axiz.core.tokenization.Token;
import pe.axiz.core.tokenization.Tokenizer;
import pe.axiz.core.tokenization.vault.TokenVault;
import pe.axiz.kit.jpa.EncryptedConverter;

/**
 * Entradas de análisis estático válidas e inválidas de forma intencional; nunca se instalan como
 * mapeos de aplicación.
 */
public class DataShapes {
  public record RawPan(String maskedPan) {}

  public record NumericPan(long cardNumber) {}

  public record TypedPan(Pan pan) {}

  public record Truncated(TruncatedPan maskedPan) {}

  public record Unsupported(String pinBlock) {}

  /** Secretos de tarjeta cuyo nombre no termina con el término sensible. */
  public record InnerTerms(String cvvCode, String pinCode) {}

  public record CardToken(Token cardToken) {}

  public record SafeFields(EncryptedValue documentNumber, KeyedHash documentIndex) {}

  public record Client(Tokenizer tokenizer) {}

  public record StolenVault(TokenVault vault) {}

  @Entity
  public static class DirectSad {
    CardVerificationCode code;
  }

  @Entity
  public static class ListSad {
    List<CardVerificationCode> codes;
  }

  @Entity
  public static class OptionalSad {
    Optional<CardVerificationCode> code;
  }

  @Entity
  public static class ArraySad {
    CardVerificationCode[] codes;
  }

  public record Nested(CardVerificationCode code) {}

  @Entity
  public static class EmbeddedSad {
    Nested details;
  }

  public record SagaContext(CardVerificationCode value) {}

  public static class SagaUse {
    SagaDefinition<SagaContext> definition;
  }

  public static class Encryption extends EncryptedConverter {
    public Encryption(FieldEncryptor encryptor) {
      super(encryptor);
    }

    protected String context() {
      return "purpose:document";
    }
  }

  @Converter(autoApply = true)
  public static class AutoEncryption extends Encryption {
    public AutoEncryption(FieldEncryptor encryptor) {
      super(encryptor);
    }
  }

  @Entity
  @Access(AccessType.FIELD)
  public static class FieldMapped {
    @Id String id;

    @Convert(converter = Encryption.class)
    String dni;
  }

  @Entity
  @Access(AccessType.PROPERTY)
  public static class GetterMapped {
    String id;
    String dni;

    @Id
    public String getId() {
      return id;
    }

    @Convert(converter = Encryption.class)
    public String getDni() {
      return dni;
    }
  }

  @Entity
  @Access(AccessType.PROPERTY)
  public static class WrongSide {
    String id;

    @Convert(converter = Encryption.class)
    String dni;

    @Id
    public String getId() {
      return id;
    }

    public String getDni() {
      return dni;
    }
  }

  @Entity
  public static class Unprotected {
    @Sensitive String personal;
  }

  @Entity
  public static class AutoMapped {
    String dni;
  }

  @Entity
  public static class Disabled {
    @Convert(disableConversion = true)
    String dni;
  }

  @Entity
  public static class Explicit {
    EncryptedValue dni;
    Token cardToken;
    KeyedHash accountNumber;
  }

  @Entity
  public static class Deep {
    Level1 nested;
  }

  public static class Level1 {
    Level2 nested;
  }

  public static class Level2 {
    Level3 nested;
  }

  public static class Level3 {
    Level4 nested;
  }

  public static class Level4 {
    Level5 nested;
  }

  public static class Level5 {
    String data;
  }

  @Entity
  @Access(AccessType.PROPERTY)
  public static class Computed {
    public String getDni() {
      return "computed";
    }
  }

  @Entity
  @Access(AccessType.PROPERTY)
  public static class ComputedProtected {
    @Convert(converter = Encryption.class)
    public String getDni() {
      return "computed";
    }
  }
}
