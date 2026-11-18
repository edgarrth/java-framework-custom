package pe.axiz.spring.inbox.r2dbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import pe.axiz.adapter.inbox.r2dbc.R2dbcInboxStore;
import pe.axiz.core.inbox.InboxException;
import pe.axiz.core.inbox.InboxStore;
import pe.axiz.core.messaging.Deduplicator;
import pe.axiz.kit.r2dbc.R2dbcSql;
import pe.axiz.kit.r2dbc.ReactiveSql;
import pe.axiz.spring.inbox.AxizInboxAutoConfiguration;
import pe.axiz.test.support.r2dbc.FakeR2dbc;

class InboxR2dbcTest {

  @Test
  void reactiveServicesDeduplicateOverR2dbc() {
    Set<String> rows = ConcurrentHashMap.newKeySet();
    FakeR2dbc db =
        new FakeR2dbc()
            // startup schema check: the table has every column of this version (owner included)
            .onQuery(
                "SELECT consumer, message_id, status, owner, lease_until, expires_at",
                b -> List.of())
            .onUpdate("INSERT INTO axiz_inbox", b -> rows.add(b.get(0) + "/" + b.get(1)) ? 1L : 0L)
            .onUpdate("SET owner", b -> 0L)
            .onQuery(
                "SELECT status, lease_until",
                b -> List.of(Map.of("status", "PROCESSED", "lease_until", Instant.EPOCH)))
            .onUpdate("SET status = 'PROCESSED'", b -> 1L);
    new ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                AxizInboxR2dbcAutoConfiguration.class, AxizInboxAutoConfiguration.class))
        .withPropertyValues("axiz.inbox.store=r2dbc")
        .withBean(ReactiveSql.class, () -> new R2dbcSql(db))
        .run(
            ctx -> {
              assertInstanceOf(R2dbcInboxStore.class, ctx.getBean(InboxStore.class));
              Deduplicator d = ctx.getBean(Deduplicator.class);
              Deduplicator.Claim billing = d.claim("billing", "e-1");
              assertEquals(Deduplicator.Claim.Status.ACQUIRED, billing.status());
              assertTrue(d.completed("billing", "e-1", billing.token()));
              assertEquals(
                  Deduplicator.Claim.Status.ALREADY_PROCESSED, d.claim("billing", "e-1").status());
              assertEquals(Deduplicator.Claim.Status.ACQUIRED, d.claim("ledger", "e-1").status());
              assertEquals(2, rows.size());
            });
  }

  @Test
  void anInboxTableOfAnOlderVersionStopsTheStartup() {
    // the schema query fails as it does on a table without the owner column
    FakeR2dbc db = new FakeR2dbc();
    new ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                AxizInboxR2dbcAutoConfiguration.class, AxizInboxAutoConfiguration.class))
        .withPropertyValues("axiz.inbox.store=r2dbc")
        .withBean(ReactiveSql.class, () -> new R2dbcSql(db))
        .run(
            ctx -> {
              Throwable failure = ctx.getStartupFailure();
              assertInstanceOf(InboxException.class, failure);
              assertTrue(failure.getMessage().contains("inbox-postgresql.sql"));
            });
  }
}
