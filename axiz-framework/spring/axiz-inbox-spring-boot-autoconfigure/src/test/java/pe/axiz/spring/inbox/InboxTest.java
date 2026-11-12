package pe.axiz.spring.inbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import javax.sql.DataSource;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import pe.axiz.core.messaging.Deduplicator;
import pe.axiz.spring.foundation.aws.AxizAwsIdentityAutoConfiguration;

class InboxTest {

  @Test
  void duplicatesAreDetectedPerConsumer() {
    JdbcDataSource h2 = new JdbcDataSource();
    h2.setURL("jdbc:h2:mem:inbox;DB_CLOSE_DELAY=-1");
    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(AxizAwsIdentityAutoConfiguration.class))
        .withConfiguration(AutoConfigurations.of(AxizInboxAutoConfiguration.class))
        .withBean(DataSource.class, () -> h2)
        .run(
            ctx -> {
              Deduplicator d = ctx.getBean(Deduplicator.class);
              Deduplicator.Claim billing = d.claim("billing", "e-1");
              assertEquals(Deduplicator.Claim.Status.ACQUIRED, billing.status());
              assertTrue(d.completed("billing", "e-1", billing.token()));
              assertEquals(
                  Deduplicator.Claim.Status.ALREADY_PROCESSED, d.claim("billing", "e-1").status());
              Deduplicator.Claim ledger = d.claim("ledger", "e-1");
              assertEquals(Deduplicator.Claim.Status.BUSY, d.claim("ledger", "e-1").status());
              assertTrue(d.failed("ledger", "e-1", ledger.token()));
              assertEquals(Deduplicator.Claim.Status.ACQUIRED, d.claim("ledger", "e-1").status());
            });
  }
}
