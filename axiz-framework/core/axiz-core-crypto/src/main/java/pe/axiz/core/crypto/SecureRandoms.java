package pe.axiz.core.crypto;

import java.nio.charset.StandardCharsets;
import java.security.DrbgParameters;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Arrays;

/**
 * Explicit JCA DRBG selection; construct on the bootstrap thread to acquire its initial entropy.
 */
public final class SecureRandoms {
  private SecureRandoms() {}

  /**
   * Creates an independently seeded 256-bit DRBG, without requesting prediction resistance on every
   * generation. Does not fall back to NativePRNG or a noncryptographic generator. Provider reseed
   * limits still apply; this method does not claim that every third-party provider is nonblocking
   * or that the JVM is a validated cryptographic module.
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
