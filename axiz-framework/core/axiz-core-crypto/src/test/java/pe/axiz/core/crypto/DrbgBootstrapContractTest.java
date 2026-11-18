package pe.axiz.core.crypto;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.security.DrbgParameters;
import java.security.Provider;
import java.security.SecureRandomParameters;
import java.security.SecureRandomSpi;
import java.security.Security;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;

@ResourceLock("java-security-providers")
class DrbgBootstrapContractTest {
  private static final String NAME = "AxizTestDrbg";

  @Test
  void bootstrapConsumesEntropyAtStartupClearsProbeAndRequiresTheExactProviderPolicy() {
    var accepted =
        new RecordingSpi(
            DrbgParameters.instantiation(256, DrbgParameters.Capability.RESEED_ONLY, null));
    var provider = new TestProvider(accepted);
    Security.insertProviderAt(provider, 1);
    try {
      var random = SecureRandoms.drbg("token-vault");
      assertEquals(NAME, random.getProvider().getName());
      assertEquals(256, provider.requested.getStrength());
      assertEquals(DrbgParameters.Capability.RESEED_ONLY, provider.requested.getCapability());
      assertArrayEquals(
          "token-vault".getBytes(java.nio.charset.StandardCharsets.US_ASCII),
          provider.requested.getPersonalizationString());
      assertNotNull(accepted.bootstrap);
      assertEquals(32, accepted.bootstrap.length);
      assertArrayEquals(new byte[32], accepted.bootstrap);
    } finally {
      Security.removeProvider(NAME);
    }
    for (var rejected :
        List.of(
            new RecordingSpi(null),
            new RecordingSpi(
                DrbgParameters.instantiation(128, DrbgParameters.Capability.RESEED_ONLY, null)),
            new RecordingSpi(
                DrbgParameters.instantiation(
                    256, DrbgParameters.Capability.PR_AND_RESEED, null)))) {
      Security.insertProviderAt(new TestProvider(rejected), 1);
      try {
        assertThrows(CryptoException.class, () -> SecureRandoms.drbg("token-vault"));
      } finally {
        Security.removeProvider(NAME);
      }
    }
  }

  private static final class TestProvider extends Provider {
    private static final long serialVersionUID = 1L;
    private DrbgParameters.Instantiation requested;

    TestProvider(RecordingSpi spi) {
      super(NAME, "1", "Test-only recording random provider");
      putService(
          new Service(
              this, "SecureRandom", "DRBG", RecordingSpi.class.getName(), List.of(), Map.of()) {
            @Override
            public Object newInstance(Object parameters) {
              requested = (DrbgParameters.Instantiation) parameters;
              return spi;
            }
          });
    }
  }

  private static final class RecordingSpi extends SecureRandomSpi {
    private static final long serialVersionUID = 1L;
    private final SecureRandomParameters parameters;
    private byte[] bootstrap;

    RecordingSpi(SecureRandomParameters parameters) {
      this.parameters = parameters;
    }

    @Override
    protected void engineSetSeed(byte[] seed) {}

    @Override
    protected void engineNextBytes(byte[] bytes) {
      Arrays.fill(bytes, (byte) 93);
      bootstrap = bytes;
    }

    @Override
    protected byte[] engineGenerateSeed(int count) {
      return new byte[count];
    }

    @Override
    protected SecureRandomParameters engineGetParameters() {
      return parameters;
    }
  }
}
