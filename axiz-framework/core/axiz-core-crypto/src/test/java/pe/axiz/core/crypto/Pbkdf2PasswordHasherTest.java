package pe.axiz.core.crypto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Base64;
import org.junit.jupiter.api.Test;

/** Stored hashes are read by one validating parser: corrupt records never match nor throw. */
class Pbkdf2PasswordHasherTest {

  private static final Base64.Encoder B64 = Base64.getEncoder().withoutPadding();
  private static final String SALT = B64.encodeToString(new byte[16]);
  private static final String HASH = B64.encodeToString(new byte[32]);

  private final Pbkdf2PasswordHasher hasher =
      new Pbkdf2PasswordHasher(Pbkdf2PasswordHasher.MIN_ITERATIONS);

  @Test
  void aFreshHashMatchesOnlyItsPassword() {
    String stored = hasher.hash("s3cret".toCharArray());
    assertTrue(stored.startsWith("$pbkdf2-sha256$i=100000$"), stored);
    assertTrue(hasher.matches("s3cret".toCharArray(), stored));
    assertFalse(hasher.matches("S3cret".toCharArray(), stored));
    assertNotEquals(stored, hasher.hash("s3cret".toCharArray()), "every hash has its own salt");
    assertFalse(hasher.needsUpgrade(stored));
    assertTrue(new Pbkdf2PasswordHasher(200_000).needsUpgrade(stored));
  }

  @Test
  void corruptOrForeignRecordsNeverMatchAndAskForAnUpgrade() {
    String[] corrupt = {
      null,
      "",
      "plain-text",
      "$2a$10$abcdefghijklmnopqrstuv",
      "$pbkdf2-sha256$i=$" + SALT + "$" + HASH,
      "$pbkdf2-sha256$i=abc$" + SALT + "$" + HASH,
      "$pbkdf2-sha256$i=-100000$" + SALT + "$" + HASH,
      "$pbkdf2-sha256$i=1000000000$" + SALT + "$" + HASH,
      "$pbkdf2-sha256$i=99999$" + SALT + "$" + HASH,
      "$pbkdf2-sha256$i=10000001$" + SALT + "$" + HASH,
      "$pbkdf2-sha256$i=100000$" + SALT,
      "$pbkdf2-sha256$i=100000$" + SALT + "$" + HASH + "$extra",
      "$pbkdf2-sha256$i=100000$***$" + HASH,
      "$pbkdf2-sha256$i=100000$" + SALT + "$***",
      "$pbkdf2-sha256$i=100000$" + B64.encodeToString(new byte[8]) + "$" + HASH,
      "$pbkdf2-sha256$i=100000$" + SALT + "$" + B64.encodeToString(new byte[31]),
      "$pbkdf2-sha256$i=100000$$",
    };
    for (String record : corrupt) {
      assertFalse(hasher.matches("x".toCharArray(), record), String.valueOf(record));
      assertTrue(hasher.needsUpgrade(record), String.valueOf(record));
      assertTrue(Pbkdf2PasswordHasher.parse(record).isEmpty(), String.valueOf(record));
    }
  }

  @Test
  void aWellFormedRecordIsReadWithItsOwnCost() {
    var stored =
        Pbkdf2PasswordHasher.parse("$pbkdf2-sha256$i=250000$" + SALT + "$" + HASH).orElseThrow();
    assertEquals(250_000, stored.iterations());
    assertEquals(16, stored.salt().length);
    stored.salt()[0] = 9;
    assertEquals(0, stored.salt()[0], "the parsed record hands out copies");
    stored.hash()[0] = 9;
    assertEquals(0, stored.hash()[0]);
  }

  @Test
  void theConfiguredCostMustBeWithinBounds() {
    assertThrows(CryptoException.class, () -> new Pbkdf2PasswordHasher(99_999));
    assertThrows(CryptoException.class, () -> new Pbkdf2PasswordHasher(10_000_001));
    new Pbkdf2PasswordHasher(Pbkdf2PasswordHasher.MAX_ITERATIONS);
  }
}
