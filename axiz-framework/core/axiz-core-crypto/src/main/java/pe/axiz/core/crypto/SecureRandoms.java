package pe.axiz.core.crypto;

import java.nio.charset.StandardCharsets;
import java.security.DrbgParameters;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Arrays;

/**
 * Selección explícita de DRBG de JCA; se construye en el hilo de arranque para adquirir su entropía
 * inicial.
 */
public final class SecureRandoms {
  private SecureRandoms() {}

  /**
   * Crea un DRBG de 256 bits con semilla independiente, sin solicitar resistencia a la predicción
   * en cada generación. No recurre a NativePRNG ni a un generador no criptográfico. Los límites de
   * resiembra del proveedor siguen aplicando; este método no afirma que todo proveedor de terceros
   * sea no bloqueante ni que la JVM sea un módulo criptográfico validado.
   */
  public static SecureRandom drbg(String purpose) {
    if (purpose == null || !purpose.matches("[a-z][a-z0-9.-]{0,63}"))
      throw new IllegalArgumentException("Invalid random generator purpose");
    try {
      SecureRandom random =
          SecureRandom.getInstance(
              "DRBG",
              DrbgParameters.instantiation(
                  256,
                  DrbgParameters.Capability.RESEED_ONLY,
                  purpose.getBytes(StandardCharsets.US_ASCII)));
      if (!(random.getParameters() instanceof DrbgParameters.Instantiation parameters)
          || parameters.getStrength() < 256
          || parameters.getCapability().supportsPredictionResistance())
        throw new CryptoException(
            "Random provider does not support the required generation policy");
      byte[] bootstrap = new byte[32];
      try {
        random.nextBytes(bootstrap);
      } finally {
        Arrays.fill(bootstrap, (byte) 0);
      }
      return random;
    } catch (NoSuchAlgorithmException unavailable) {
      throw new CryptoException("A 256-bit DRBG provider is required", unavailable);
    }
  }
}
