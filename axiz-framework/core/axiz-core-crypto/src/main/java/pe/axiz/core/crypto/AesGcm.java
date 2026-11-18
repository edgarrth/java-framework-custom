package pe.axiz.core.crypto;

import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.jspecify.annotations.Nullable;

/**
 * AES-256-GCM authenticated encryption with a random 96-bit IV per message and optional additional
 * authenticated data (AAD). Output layout: {@code iv(12) || ciphertext+tag(16)}.
 */
public final class AesGcm {

  public static final int KEY_BYTES = 32;
  private static final int IV_BYTES = 12;
  private static final int TAG_BITS = 128;
  private static final String TRANSFORMATION = "AES/GCM/NoPadding";
  private static final SecureRandom RANDOM = SecureRandoms.drbg("aes-gcm");

  private AesGcm() {}

  /** Initializes entropy and JCA providers during startup, before an event-loop conversion. */
  public static void initialize() {
    encrypt(newKey(), new byte[0], new byte[0]);
  }

  public static SecretKey newKey() {
    try {
      KeyGenerator generator = KeyGenerator.getInstance("AES");
      generator.init(KEY_BYTES * 8, RANDOM);
      return generator.generateKey();
    } catch (GeneralSecurityException e) {
      throw new CryptoException("AES key generation failed", e);
    }
  }

  public static SecretKey key(byte[] raw) {
    if (raw.length != KEY_BYTES) {
      throw new CryptoException("AES-256 keys must have 32 bytes");
    }
    return new SecretKeySpec(raw, "AES");
  }

  public static byte[] encrypt(SecretKey key, byte[] plaintext, byte @Nullable [] aad) {
    byte[] iv = new byte[IV_BYTES];
    RANDOM.nextBytes(iv);
    try {
      Cipher cipher = Cipher.getInstance(TRANSFORMATION);
      cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
      if (aad != null) {
        cipher.updateAAD(aad);
      }
      byte[] ct = cipher.doFinal(plaintext);
      return ByteBuffer.allocate(IV_BYTES + ct.length).put(iv).put(ct).array();
    } catch (GeneralSecurityException e) {
      throw new CryptoException("Encryption failed", e);
    }
  }

  public static byte[] decrypt(SecretKey key, byte[] payload, byte @Nullable [] aad) {
    if (payload.length < IV_BYTES + TAG_BITS / 8) {
      throw new CryptoException("Ciphertext too short");
    }
    try {
      Cipher cipher = Cipher.getInstance(TRANSFORMATION);
      cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, payload, 0, IV_BYTES));
      if (aad != null) {
        cipher.updateAAD(aad);
      }
      return cipher.doFinal(payload, IV_BYTES, payload.length - IV_BYTES);
    } catch (GeneralSecurityException e) {
      throw new CryptoException("Decryption failed: wrong key or tampered data", e);
    }
  }
}
