package pe.axiz.core.crypto;

import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.MGF1ParameterSpec;
import java.security.spec.PSSParameterSpec;
import javax.crypto.Cipher;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;

/** RSA con cifrado OAEP-SHA256 y firmas RSASSA-PSS-SHA256. No se ofrece PKCS#1 v1.5. */
public final class Rsa {

  private static final String OAEP = "RSA/ECB/OAEPWithSHA-256AndMGF1Padding";
  private static final OAEPParameterSpec OAEP_SPEC =
      new OAEPParameterSpec(
          "SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT);
  private static final PSSParameterSpec PSS_SPEC =
      new PSSParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, 32, 1);

  private Rsa() {}

  public static KeyPair newKeyPair(int bits) {
    if (bits < 2048) {
      throw new CryptoException("RSA keys must have at least 2048 bits");
    }
    try {
      KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
      g.initialize(bits);
      return g.generateKeyPair();
    } catch (GeneralSecurityException e) {
      throw new CryptoException("RSA key generation failed", e);
    }
  }

  public static byte[] encrypt(PublicKey key, byte[] plaintext) {
    try {
      Cipher c = Cipher.getInstance(OAEP);
      c.init(Cipher.ENCRYPT_MODE, key, OAEP_SPEC);
      return c.doFinal(plaintext);
    } catch (GeneralSecurityException e) {
      throw new CryptoException("RSA encryption failed", e);
    }
  }

  public static byte[] decrypt(PrivateKey key, byte[] ciphertext) {
    try {
      Cipher c = Cipher.getInstance(OAEP);
      c.init(Cipher.DECRYPT_MODE, key, OAEP_SPEC);
      return c.doFinal(ciphertext);
    } catch (GeneralSecurityException e) {
      throw new CryptoException("RSA decryption failed", e);
    }
  }

  public static byte[] sign(PrivateKey key, byte[] data) {
    try {
      Signature s = Signature.getInstance("RSASSA-PSS");
      s.setParameter(PSS_SPEC);
      s.initSign(key);
      s.update(data);
      return s.sign();
    } catch (GeneralSecurityException e) {
      throw new CryptoException("RSA signature failed", e);
    }
  }

  public static boolean verify(PublicKey key, byte[] data, byte[] signature) {
    try {
      Signature s = Signature.getInstance("RSASSA-PSS");
      s.setParameter(PSS_SPEC);
      s.initVerify(key);
      s.update(data);
      return s.verify(signature);
    } catch (GeneralSecurityException e) {
      return false;
    }
  }
}
