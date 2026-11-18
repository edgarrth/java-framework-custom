package pe.axiz.quality.archunit.sample.sensitive.model;

import jakarta.persistence.Access;
import jakarta.persistence.AccessType;
import jakarta.persistence.Convert;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import pe.axiz.core.carddata.CardVerificationCode;
import pe.axiz.core.carddata.Pan;
import pe.axiz.core.crypto.EncryptedValue;
import pe.axiz.core.crypto.FieldEncryptor;
import pe.axiz.core.sensitive.Sensitive;
import pe.axiz.core.sensitive.SensitivityLevel;
import pe.axiz.kit.dynamodb.EncryptedAttributeConverter;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbConvertedBy;

/** Mapping canaries; not executable persistence entities. */
public class MappingShapes {
  public abstract static class DynamoEncryption extends EncryptedAttributeConverter {
    protected DynamoEncryption(FieldEncryptor encryptor) {
      super(encryptor);
    }
  }

  @DynamoDbBean
  public static class DynamoGood {
    String dni;

    @DynamoDbConvertedBy(DynamoEncryption.class)
    public String getDni() {
      return dni;
    }
  }

  @DynamoDbBean
  public static class DynamoBad {
    String dni;

    public String getDni() {
      return dni;
    }
  }

  @DynamoDbBean
  public static class DynamoComputed {
    @DynamoDbConvertedBy(DynamoEncryption.class)
    public String getDni() {
      return "document";
    }
  }

  @DynamoDbBean
  public static class DynamoNumeric {
    @DynamoDbConvertedBy(DynamoEncryption.class)
    public long getDni() {
      return 1;
    }
  }

  @Entity
  public static class FieldId {
    @Id String id;

    @Convert(converter = DataShapes.Encryption.class)
    String dni;
  }

  @Entity
  public static class EmbeddedFieldId {
    @EmbeddedId String id;

    @Convert(converter = DataShapes.Encryption.class)
    String dni;
  }

  @Entity
  public static class GetterId {
    String dni;

    @Id
    public String getId() {
      return "id";
    }

    @Convert(converter = DataShapes.Encryption.class)
    public String getDni() {
      return dni;
    }
  }

  @Entity
  public static class EmbeddedGetterId {
    String dni;

    @EmbeddedId
    public String getId() {
      return "id";
    }

    @Convert(converter = DataShapes.Encryption.class)
    public String getDni() {
      return dni;
    }
  }

  @Entity
  @Access(AccessType.PROPERTY)
  public static class FieldOverride {
    @Access(AccessType.FIELD)
    @Convert(converter = DataShapes.Encryption.class)
    String dni;

    public String getDni() {
      return dni;
    }
  }

  @Entity
  @Access(AccessType.FIELD)
  public static class GetterOverride {
    String dni;

    @Access(AccessType.PROPERTY)
    @Convert(converter = DataShapes.Encryption.class)
    public String getDni() {
      return dni;
    }
  }

  @Entity
  public static class ConflictingOverrides {
    @Access(AccessType.FIELD)
    @Convert(converter = DataShapes.Encryption.class)
    String dni;

    @Access(AccessType.PROPERTY)
    @Convert(converter = DataShapes.Encryption.class)
    public String getDni() {
      return dni;
    }
  }

  @Entity
  @Access(AccessType.PROPERTY)
  public static class MissingGetter {
    @Convert(converter = DataShapes.Encryption.class)
    String dni;
  }

  @Entity
  public static class Numeric {
    @Convert(converter = DataShapes.Encryption.class)
    long dni;
  }

  @Entity
  public static class ExplicitDefaultConverter {
    @Convert String dni;
  }

  @Entity
  @Access(AccessType.PROPERTY)
  public static class ComputedTyped {
    public EncryptedValue getDni() {
      throw new UnsupportedOperationException();
    }
  }

  @Entity
  @Access(AccessType.PROPERTY)
  public static class ComputedSad {
    public CardVerificationCode getVerification() {
      throw new UnsupportedOperationException();
    }
  }

  @Entity
  @Access(AccessType.PROPERTY)
  public static class ComputedPan {
    public Pan getPan() {
      throw new UnsupportedOperationException();
    }
  }

  @Entity
  public static class AnnotatedGetter {
    String personal;

    @Sensitive(level = SensitivityLevel.CRITICAL)
    public String getPersonal() {
      return personal;
    }
  }

  @Entity
  public static class LowSensitivity {
    @Sensitive(level = SensitivityLevel.LOW)
    String reference;

    @Sensitive(level = SensitivityLevel.MEDIUM)
    String displayName;

    public String getReference(String locale) {
      return reference;
    }

    public boolean isAvailable() {
      return true;
    }
  }

  @Entity
  public static class Cyclic {
    Cyclic next;
    static String password;
    String description;
  }
}
