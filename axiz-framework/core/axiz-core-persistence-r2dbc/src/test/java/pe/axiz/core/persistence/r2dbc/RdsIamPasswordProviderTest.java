package pe.axiz.core.persistence.r2dbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import pe.axiz.core.test.MutableClock;
import software.amazon.awssdk.services.rds.RdsUtilities;
import software.amazon.awssdk.services.rds.model.GenerateAuthenticationTokenRequest;

class RdsIamPasswordProviderTest {

  private final List<GenerateAuthenticationTokenRequest> requests = new ArrayList<>();
  private final RdsUtilities rds =
      new RdsUtilities() {
        @Override
        public String generateAuthenticationToken(GenerateAuthenticationTokenRequest request) {
          requests.add(request);
          return "token-" + requests.size();
        }
      };

  @Test
  void reusesTheTokenUntilItMustBeRenewed() {
    MutableClock clock = MutableClock.at("2026-10-11T10:00:00Z");
    RdsIamPasswordProvider p =
        new RdsIamPasswordProvider(
            rds, "db.cluster.local", 5432, "app", clock, Duration.ofMinutes(10));

    assertEquals("token-1", p.password().toString());
    clock.advance(Duration.ofMinutes(9));
    assertEquals("token-1", p.password().toString());
    clock.advance(Duration.ofMinutes(1));
    assertEquals("token-2", p.password().toString());

    GenerateAuthenticationTokenRequest r = requests.get(0);
    assertEquals("db.cluster.local", r.hostname());
    assertEquals(5432, r.port());
    assertEquals("app", r.username());
    assertTrue(!p.toString().contains("token"), "never prints the token");
  }

  @Test
  void refreshMustHappenBeforeTheTokenExpires() {
    MutableClock clock = MutableClock.at("1970-01-01T00:00:00Z");
    assertThrows(
        IllegalArgumentException.class,
        () -> new RdsIamPasswordProvider(rds, "h", 5432, "u", clock, Duration.ofMinutes(15)));
  }
}
