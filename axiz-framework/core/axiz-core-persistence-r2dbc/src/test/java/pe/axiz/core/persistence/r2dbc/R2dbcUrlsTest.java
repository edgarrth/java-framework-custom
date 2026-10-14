package pe.axiz.core.persistence.r2dbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class R2dbcUrlsTest {

  @Test
  void postgresUrlsTranslateToJdbc() {
    assertEquals(
        "jdbc:postgresql://db:5432/payments?sslmode=require",
        R2dbcUrls.toJdbc("r2dbc:postgresql://db:5432/payments?sslMode=require&maxSize=5"));
    assertEquals(
        "jdbc:postgresql://db/p",
        R2dbcUrls.toJdbc("r2dbc:postgresql://db/p?preparedStatementCacheQueries=0"));
    assertEquals(
        "jdbc:postgresql://db/payments", R2dbcUrls.toJdbc("r2dbc:pool:postgresql://db/payments"));
    assertThrows(IllegalArgumentException.class, () -> R2dbcUrls.toJdbc("r2dbc:mysql://db/x"));
    assertThrows(IllegalArgumentException.class, () -> R2dbcUrls.toJdbc("jdbc:postgresql://x/y"));
  }

  @Test
  void endpointsDefaultToThePostgresPort() {
    assertEquals(
        new R2dbcUrls.Endpoint("cluster.aws", 5432, "payments"),
        R2dbcUrls.endpoint("r2dbc:postgresql://cluster.aws/payments"));
    assertEquals(
        new R2dbcUrls.Endpoint("h", 6432, "db"),
        R2dbcUrls.endpoint("r2dbc:postgresql://h:6432/db"));
  }

  @Test
  void encryptedTextNeverPrintsItsValue() {
    assertEquals("EncryptedText[****]", EncryptedText.of("1910012345678").toString());
  }
}
