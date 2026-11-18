package pe.axiz.spring.inbox.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import javax.sql.DataSource;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import pe.axiz.adapter.inbox.jdbc.JdbcInboxStore;
import pe.axiz.core.inbox.InboxStore;
import pe.axiz.kit.jdbc.JdbcTransactions;
import pe.axiz.spring.foundation.InboxCompletion;
import pe.axiz.spring.inbox.AxizInboxAutoConfiguration;
import pe.axiz.spring.inbox.AxizInboxProperties;

class InboxCompletionWiringTest {
  private JdbcDataSource source() {
    var source = new JdbcDataSource();
    source.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
    return source;
  }

  @Test
  void jdbcCompletionExposesTheActualSpringDataSourceAndCompletesTheClaim() {
    var source = source();
    new ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                AxizInboxAutoConfiguration.class, AxizInboxJdbcAutoConfiguration.class))
        .withBean(DataSource.class, () -> source)
        .run(
            ctx -> {
              assertThat(ctx).hasNotFailed();
              var completion = ctx.getBean(InboxCompletion.class);
              assertSame(source, completion.transactionResource());
              Instant now = Instant.now();
              ctx.getBean(InboxStore.class)
                  .tryAcquire(
                      "consumer", "key", "owner", now, now.plusSeconds(60), now.plusSeconds(3600));
              assertTrue(completion.complete("consumer", "key", "owner"));
            });
  }

  @Test
  void aStoreWithoutTheSpringConnectionProviderCannotClaimSharedTransactionAtomicity() {
    for (boolean jdbc : new boolean[] {false, true}) {
      var source = source();
      InboxStore store =
          jdbc
              ? new JdbcInboxStore(new JdbcTransactions(source), "axiz_inbox")
              : org.mockito.Mockito.mock(InboxStore.class);
      new ApplicationContextRunner()
          .withConfiguration(
              AutoConfigurations.of(
                  AxizInboxAutoConfiguration.class, AxizInboxJdbcAutoConfiguration.class))
          .withBean(DataSource.class, () -> source)
          .withBean(InboxStore.class, () -> store)
          .run(
              ctx -> {
                assertThat(ctx).hasNotFailed();
                assertNull(ctx.getBeanProvider(InboxCompletion.class).getIfAvailable());
              });
    }
  }

  @Test
  void transactionalModeRejectsUnknownValues() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new AxizInboxProperties(
                "jdbc",
                Duration.ofMinutes(5),
                Duration.ofDays(14),
                Duration.ofHours(1),
                "sometimes"));
  }
}
