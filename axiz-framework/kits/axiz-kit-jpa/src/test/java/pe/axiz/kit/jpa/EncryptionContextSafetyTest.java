package pe.axiz.kit.jpa;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Modifier;
import java.util.Map;
import org.junit.jupiter.api.Test;
import pe.axiz.core.crypto.AesGcm;
import pe.axiz.core.crypto.CryptoException;
import pe.axiz.core.crypto.FieldEncryptor;
import pe.axiz.core.crypto.StaticKeyProvider;

class EncryptionContextSafetyTest {
  @Test
  void explicitPurposeCanBeReboundWithoutWaitingForKeyRotation() {
    var encryptor = new FieldEncryptor(new StaticKeyProvider("k", Map.of("k", AesGcm.newKey())));
    String original = encryptor.encrypt("123", "account.oldNumber");
    assertFalse(encryptor.needsRotation(original));
    String migrated = encryptor.reencrypt(original, "account.oldNumber", "account.number");
    assertEquals("123", encryptor.decrypt(migrated, "account.number"));
    assertThrows(CryptoException.class, () -> encryptor.decrypt(migrated, "account.oldNumber"));
  }

  @Test
  void convertersRequireAnExplicitPurposeAndRejectTheGenericContext() {
    assertTrue(
        Modifier.isAbstract(EncryptedConverter.class.getModifiers()),
        "a column must choose its context");
    FieldEncryptor encryptor =
        new FieldEncryptor(new StaticKeyProvider("k", Map.of("k", AesGcm.newKey())));
    EncryptedConverter generic =
        new EncryptedConverter(encryptor) {
          protected String context() {
            return "axiz.field";
          }
        };
    assertThrows(IllegalArgumentException.class, () -> generic.convertToDatabaseColumn("123"));
  }
}
