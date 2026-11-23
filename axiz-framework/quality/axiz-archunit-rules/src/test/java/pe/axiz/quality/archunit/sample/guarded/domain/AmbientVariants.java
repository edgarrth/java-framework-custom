package pe.axiz.quality.archunit.sample.guarded.domain;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Random;
import javax.crypto.Cipher;
import javax.crypto.NoSuchPaddingException;

/**
 * Tiempo ambiental, motores criptográficos directos y aleatoriedad predecible que las reglas deben
 * rechazar.
 */
public class AmbientVariants {

  public LocalDate today() {
    return LocalDate.now(ZoneId.of("America/Lima"));
  }

  public Instant system() {
    return Instant.now(Clock.systemUTC());
  }

  public int draw() {
    return new Random().nextInt(10);
  }

  public byte[] digest(byte[] data) throws NoSuchAlgorithmException {
    return MessageDigest.getInstance("SHA-256").digest(data); // la regla rechaza todo motor directo
  }

  public Cipher cipher() throws NoSuchAlgorithmException, NoSuchPaddingException {
    return Cipher.getInstance("AES/GCM/NoPadding"); // la regla rechaza todo motor directo
  }
}
