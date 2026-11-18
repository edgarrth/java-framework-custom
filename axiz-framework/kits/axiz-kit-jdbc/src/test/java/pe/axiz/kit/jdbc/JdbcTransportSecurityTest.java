package pe.axiz.kit.jdbc;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Properties;
import org.junit.jupiter.api.Test;
import pe.axiz.core.commons.net.DataTransportSecurityPolicy;

class JdbcTransportSecurityTest {
  @Test
  void embeddedDatabasesAndBooleanTlsAliasesRespectTheSameDeploymentPolicy() {
    for (String url : new String[] {"jdbc:h2:mem:test", "jdbc:hsqldb:mem:test"}) {
      assertThrows(
          IllegalStateException.class,
          () ->
              JdbcTransportSecurity.validate(
                  url, new Properties(), new DataTransportSecurityPolicy("prod", true)));
      assertDoesNotThrow(
          () ->
              JdbcTransportSecurity.validate(
                  url, new Properties(), new DataTransportSecurityPolicy("test", false)));
    }
    for (String query :
        new String[] {
          "ssl",
          "ssl=",
          "ssl=true",
          "sslmode=verify-full&sslfactory=org.postgresql.ssl.DefaultJavaSSLFactory"
        }) {
      assertDoesNotThrow(
          () ->
              JdbcTransportSecurity.validate(
                  "jdbc:postgresql://database/payments?" + query, new Properties(), production));
    }
    assertThrows(
        IllegalStateException.class,
        () ->
            JdbcTransportSecurity.validate(
                "jdbc:postgresql://database/payments?ssl=false", new Properties(), production));
  }

  private final DataTransportSecurityPolicy production =
      new DataTransportSecurityPolicy("prod", true);

  @Test
  void postgresqlModesAndActualUrlPrecedenceAreEnforced() {
    for (String prefix : new String[] {"jdbc:postgresql:", "jdbc:aws-wrapper:postgresql:"}) {
      String url = prefix + "//database.example.test/payments";
      for (String mode : new String[] {"disable", "allow", "prefer", "require", "verify-ca"})
        assertThrows(
            IllegalStateException.class,
            () ->
                JdbcTransportSecurity.validate(
                    url + "?sslmode=" + mode, new Properties(), production));
      var defaults = new Properties();
      defaults.setProperty("sslmode", "verify-full");
      assertDoesNotThrow(() -> JdbcTransportSecurity.validate(url, defaults, production));
      assertThrows(
          IllegalStateException.class,
          () -> JdbcTransportSecurity.validate(url + "?sslmode=disable", defaults, production));
      defaults.setProperty("sslmode", "disable");
      assertDoesNotThrow(
          () -> JdbcTransportSecurity.validate(url + "?sslmode=verify-full", defaults, production));
    }
  }

  @Test
  void opaqueSocketFactoriesAndHostnameVerifiersCannotClaimVerifiedTls() {
    for (String option : new String[] {"sslfactory", "sslhostnameverifier"}) {
      var properties = new Properties();
      properties.setProperty(option, "example.CustomTrustAll");
      assertThrows(
          IllegalStateException.class,
          () ->
              JdbcTransportSecurity.validate(
                  "jdbc:postgresql://database/payments?sslmode=verify-full",
                  properties,
                  production));
    }
    var verified = new Properties();
    verified.setProperty("sslfactory", "org.postgresql.ssl.LibPQFactory");
    verified.setProperty("sslhostnameverifier", "org.postgresql.ssl.PGjdbcHostnameVerifier");
    assertDoesNotThrow(
        () ->
            JdbcTransportSecurity.validate(
                "jdbc:postgresql://database/payments?sslmode=verify-full", verified, production));
  }

  @Test
  void explicitlyNonValidatingFactoriesAndAmbiguousOptionsAreRejected() {
    var properties = new Properties();
    properties.setProperty("sslfactory", "org.postgresql.ssl.NonValidatingFactory");
    assertThrows(
        IllegalStateException.class,
        () ->
            JdbcTransportSecurity.validate(
                "jdbc:postgresql://database/payments?sslmode=verify-full", properties, production));
    assertThrows(
        IllegalStateException.class,
        () ->
            JdbcTransportSecurity.validate(
                "jdbc:postgresql://database/payments?sslmode=verify-full&sslmode=disable",
                new Properties(),
                production));
  }
}
