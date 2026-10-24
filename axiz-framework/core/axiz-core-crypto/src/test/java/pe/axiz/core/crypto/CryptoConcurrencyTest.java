package pe.axiz.core.crypto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * The encryptor and the blind index are shared singletons in a service: under concurrent use every
 * ciphertext gets its own IV, every value decrypts back, and the index stays deterministic.
 */
class CryptoConcurrencyTest {

  private static final int OPERATIONS = 1_000;
  private static final String ACCOUNT = "00219100123456789015";

  @Test
  void concurrentEncryptionNeverReusesAnIvAndAlwaysDecrypts() throws Exception {
    FieldEncryptor encryptor =
        new FieldEncryptor(new StaticKeyProvider("k1", Map.of("k1", AesGcm.newKey())));
    BlindIndex index = new BlindIndex(AesGcm.newKey().getEncoded(), 16);
    Set<String> ciphertexts = ConcurrentHashMap.newKeySet();
    Set<String> indexes = ConcurrentHashMap.newKeySet();
    CountDownLatch start = new CountDownLatch(1);
    List<Future<String>> results = new ArrayList<>();
    try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
      for (int i = 0; i < OPERATIONS; i++) {
        results.add(
            pool.submit(
                () -> {
                  assertTrue(start.await(10, TimeUnit.SECONDS));
                  String cipher = encryptor.encrypt(ACCOUNT, "account.number");
                  ciphertexts.add(cipher);
                  indexes.add(index.index(ACCOUNT, "account.number"));
                  return encryptor.decrypt(cipher, "account.number");
                }));
      }
      start.countDown();
      for (Future<String> r : results) {
        assertEquals(ACCOUNT, r.get(30, TimeUnit.SECONDS));
      }
    }
    assertEquals(OPERATIONS, ciphertexts.size(), "an IV was reused");
    assertEquals(1, indexes.size(), "the blind index must be deterministic");
  }
}
