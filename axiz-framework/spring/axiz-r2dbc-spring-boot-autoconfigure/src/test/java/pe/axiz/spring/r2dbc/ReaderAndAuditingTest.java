package pe.axiz.spring.r2dbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.r2dbc.spi.Closeable;
import io.r2dbc.spi.ConnectionFactory;
import io.r2dbc.spi.ConnectionFactoryOptions;
import io.r2dbc.spi.Option;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.r2dbc.ConnectionFactoryDecorator;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.auditing.DateTimeProvider;
import org.springframework.data.auditing.ReactiveIsNewAwareAuditingHandler;
import org.springframework.data.r2dbc.convert.R2dbcCustomConversions;
import org.springframework.data.r2dbc.dialect.PostgresDialect;
import org.springframework.data.r2dbc.mapping.R2dbcMappingContext;
import org.springframework.r2dbc.core.DatabaseClient;
import pe.axiz.core.crypto.AesGcm;
import pe.axiz.core.crypto.FieldEncryptor;
import pe.axiz.core.crypto.StaticKeyProvider;
import pe.axiz.kit.r2dbc.EncryptedText;
import pe.axiz.test.support.r2dbc.FakeR2dbc;
import reactor.core.publisher.Mono;

/** The read replica connection, IAM tokens, Flyway over IAM and the auditing of rows. */
class ReaderAndAuditingTest {

  private static final Duration T = Duration.ofSeconds(5);
  private static final String READER = "r2dbc:axiztest://reader.cluster-ro.local:5433/payments";

  @BeforeEach
  void localCredentials() {
    RecordingDriver.CREATED.clear();
    // placeholders for the SDK default chain: an IAM token is signed locally, no call is made
    System.setProperty("aws.accessKeyId", "local-only-access-key");
    System.setProperty("aws.secretAccessKey", "local-only-secret-key");
  }

  @AfterEach
  void clearCredentials() {
    System.clearProperty("aws.accessKeyId");
    System.clearProperty("aws.secretAccessKey");
    System.clearProperty("aws.region");
  }

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withPropertyValues(
              "axiz.config.environment=test", "axiz.transport.allow-insecure-data-stores=true")
          .withPropertyValues("axiz.r2dbc.encryption-context=account.number")
          .withConfiguration(AutoConfigurations.of(AxizR2dbcAutoConfiguration.class))
          .withPropertyValues("axiz.r2dbc.encryption-context=account.number");

  private static ConnectionFactoryOptions behindThePool() {
    assertEquals(1, RecordingDriver.CREATED.size(), "the pool created one driver factory");
    return RecordingDriver.CREATED.get(0);
  }

  @Test
  void theReaderIsAPoolWithTheWriterDriverOptionsAndItsOwnSize() {
    AtomicInteger decorated = new AtomicInteger();
    ConnectionFactoryDecorator counting =
        cf -> {
          decorated.incrementAndGet();
          return cf;
        };
    runner
        .withPropertyValues(
            "axiz.r2dbc.reader.url=" + READER,
            "axiz.r2dbc.reader.max-size=4",
            "spring.r2dbc.username=app",
            "spring.r2dbc.password=local-only",
            "spring.r2dbc.properties.sslMode=verify-full")
        .withBean(ConnectionFactoryDecorator.class, () -> counting)
        .run(
            ctx -> {
              ReaderDatabase reader = ctx.getBean(ReaderDatabase.class);
              assertInstanceOf(Closeable.class, reader.connectionFactory(), "pooled");
              assertEquals(1, decorated.get(), "the same decorators as the writer");
              assertThat(reader.client()).isNotNull();
              assertEquals("DatabaseClientReactiveSql", reader.sql().toString());
              ConnectionFactoryOptions driver = behindThePool();
              assertEquals(
                  "reader.cluster-ro.local", driver.getValue(ConnectionFactoryOptions.HOST));
              assertEquals("app", driver.getValue(ConnectionFactoryOptions.USER));
              assertEquals(
                  "local-only", String.valueOf(driver.getValue(ConnectionFactoryOptions.PASSWORD)));
              assertEquals("verify-full", driver.getValue(Option.valueOf("sslMode")));
            });
  }

  @Test
  void aReaderWithoutUserOrPasswordLeavesThemToTheUrl() {
    ConnectionFactory cf =
        ReaderConnections.create(
            READER,
            1,
            null,
            null,
            null,
            Map.of(),
            List.of(),
            new pe.axiz.core.commons.net.DataTransportSecurityPolicy("test", true));
    try {
      ConnectionFactoryOptions driver = behindThePool();
      assertNull(driver.getValue(ConnectionFactoryOptions.USER));
      assertNull(driver.getValue(ConnectionFactoryOptions.PASSWORD));
    } finally {
      Mono.from(((Closeable) cf).close()).block(T);
    }
  }

  @Test
  void theReaderClosesItsPoolAndToleratesAPlainFactory() {
    ConnectionFactory pooled =
        ReaderConnections.create(
            READER,
            2,
            "app",
            "local-only",
            null,
            Map.of(),
            List.of(),
            new pe.axiz.core.commons.net.DataTransportSecurityPolicy("test", true));
    new ReaderDatabase(pooled).destroy();
    new ReaderDatabase(new FakeR2dbc()).destroy();
  }

  @Test
  void anApplicationEncryptorIsPreferredForEncryptedColumns() {
    FieldEncryptor enc =
        new FieldEncryptor(new StaticKeyProvider("k1", Map.of("k1", AesGcm.newKey())));
    FakeR2dbc db = new FakeR2dbc();
    runner
        .withBean(DatabaseClient.class, () -> DatabaseClient.create(db))
        .withBean(FieldEncryptor.class, () -> enc)
        .run(
            ctx -> {
              R2dbcCustomConversions conversions = ctx.getBean(R2dbcCustomConversions.class);
              assertTrue(conversions.hasCustomWriteTarget(EncryptedText.class));
              assertFalse(ctx.containsBean("axizReaderDatabase"), "no reader configured");
            });
  }

  /** A row with the audit columns. */
  static final class Payment extends AuditableRow {
    void assign(UUID id) {
      setId(id);
    }
  }

  /**
   * The mapping context as Spring Boot builds it: the simple types come from the dialect, so a
   * {@code UUID} id is a column and not a nested entity.
   */
  private static R2dbcMappingContext mappingContext() {
    R2dbcMappingContext context = new R2dbcMappingContext();
    context.setSimpleTypeHolder(
        R2dbcCustomConversions.of(PostgresDialect.INSTANCE).getSimpleTypeHolder());
    return context;
  }

  @Test
  void rowsAreStampedWithTheCallerAndTheServiceClock() {
    Clock fixed = Clock.fixed(Instant.parse("2026-10-23T10:15:30Z"), ZoneOffset.UTC);
    new ApplicationContextRunner()
        .withPropertyValues(
            "axiz.config.environment=test", "axiz.transport.allow-insecure-data-stores=true")
        .withPropertyValues("axiz.r2dbc.encryption-context=account.number")
        .withConfiguration(AutoConfigurations.of(AxizR2dbcAuditingAutoConfiguration.class))
        .withBean(
            "r2dbcMappingContext", R2dbcMappingContext.class, ReaderAndAuditingTest::mappingContext)
        .withBean(Clock.class, () -> fixed)
        .run(
            ctx -> {
              assertEquals(
                  Instant.parse("2026-10-23T10:15:30Z"),
                  ctx.getBean("axizR2dbcDateTimeProvider", DateTimeProvider.class)
                      .getNow()
                      .orElseThrow());
              Payment row = new Payment();
              UUID id = UUID.randomUUID();
              row.assign(id);
              assertSame(id, row.getId());
              assertTrue(row.isNew());
              assertNull(row.getVersion());
              ctx.getBean(ReactiveIsNewAwareAuditingHandler.class).markAudited(row).block(T);
              assertEquals("system", row.getCreatedBy(), "no request: the service itself");
              assertEquals("system", row.getUpdatedBy());
              assertEquals(Instant.parse("2026-10-23T10:15:30Z"), row.getCreatedAt());
              assertEquals(row.getCreatedAt(), row.getUpdatedAt());
            });
    new ApplicationContextRunner()
        .withPropertyValues(
            "axiz.config.environment=test", "axiz.transport.allow-insecure-data-stores=true")
        .withPropertyValues("axiz.r2dbc.encryption-context=account.number")
        .withConfiguration(AutoConfigurations.of(AxizR2dbcAuditingAutoConfiguration.class))
        .withBean(
            "r2dbcMappingContext", R2dbcMappingContext.class, ReaderAndAuditingTest::mappingContext)
        .withPropertyValues("axiz.r2dbc.auditing.enabled=false")
        .run(ctx -> assertFalse(ctx.containsBean("axizR2dbcAuditor")));
  }
}
