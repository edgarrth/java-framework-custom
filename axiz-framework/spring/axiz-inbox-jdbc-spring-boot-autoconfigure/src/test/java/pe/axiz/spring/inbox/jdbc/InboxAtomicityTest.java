package pe.axiz.spring.inbox.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import pe.axiz.core.messaging.DeliveryDecision;
import pe.axiz.core.messaging.EventDispatcher;
import pe.axiz.core.messaging.EventEnvelope;
import pe.axiz.core.messaging.EventHandler;
import pe.axiz.spring.inbox.AxizInboxAutoConfiguration;
import pe.axiz.spring.messaging.AxizMessagingAutoConfiguration;

class InboxAtomicityTest {
  @Test
  void aFailedCompletionRollsBackTheBusinessEffect() {
    JdbcDataSource source = new JdbcDataSource();
    source.setURL("jdbc:h2:mem:atomic-inbox-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
    JdbcTemplate jdbc = new JdbcTemplate(source);
    jdbc.execute("CREATE TABLE effects (id VARCHAR(100) PRIMARY KEY)");
    EventHandler<String> handler =
        new EventHandler<>() {
          public String eventType() {
            return "test.created";
          }

          public Class<String> payloadType() {
            return String.class;
          }

          public void handle(String payload, EventEnvelope event) {
            jdbc.update("INSERT INTO effects VALUES (?)", event.id());
          }
        };
    new ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                AxizInboxAutoConfiguration.class,
                AxizInboxJdbcAutoConfiguration.class,
                AxizMessagingAutoConfiguration.class))
        .withBean(DataSource.class, () -> source)
        .withBean(PlatformTransactionManager.class, () -> new DataSourceTransactionManager(source))
        .withBean("testHandler", EventHandler.class, () -> handler)
        .run(
            context -> {
              jdbc.execute(
                  "ALTER TABLE axiz_inbox ADD CONSTRAINT reject_completion CHECK (status <>"
                      + " 'PROCESSED')");
              var event =
                  EventEnvelope.builder()
                      .id("event-1")
                      .source("urn:test")
                      .type("test.created")
                      .time(Instant.EPOCH)
                      .subject(null)
                      .dataContentType("application/json")
                      .data("\"payload\"".getBytes(StandardCharsets.UTF_8))
                      .extensions(Map.of())
                      .build();
              DeliveryDecision decision = context.getBean(EventDispatcher.class).dispatch(event, 1);
              assertEquals(
                  0,
                  jdbc.queryForObject("SELECT COUNT(*) FROM effects", Integer.class),
                  "an inbox failure must not leave a committed business effect");
              assertEquals(DeliveryDecision.RETRY, decision);
            });
  }
}
