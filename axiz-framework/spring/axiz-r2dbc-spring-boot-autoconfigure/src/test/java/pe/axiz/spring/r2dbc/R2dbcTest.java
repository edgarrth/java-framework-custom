package pe.axiz.spring.r2dbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.r2dbc.spi.ConnectionFactory;
import io.r2dbc.spi.ConnectionFactoryOptions;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.r2dbc.autoconfigure.ConnectionFactoryOptionsBuilderCustomizer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.convert.converter.Converter;
import org.springframework.data.r2dbc.convert.R2dbcCustomConversions;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.r2dbc.connection.R2dbcTransactionManager;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.transaction.ReactiveTransactionManager;
import org.springframework.transaction.reactive.TransactionalOperator;
import pe.axiz.core.commons.RequestContext;
import pe.axiz.core.commons.RequestMetadata;
import pe.axiz.core.crypto.AesGcm;
import pe.axiz.core.crypto.FieldEncryptor;
import pe.axiz.core.crypto.StaticKeyProvider;
import pe.axiz.core.money.Money;
import pe.axiz.kit.r2dbc.DatabasePasswordProvider;
import pe.axiz.kit.r2dbc.EncryptedText;
import pe.axiz.kit.r2dbc.ReactiveSql;
import pe.axiz.kit.r2dbc.SqlParams;
import pe.axiz.test.support.r2dbc.FakeR2dbc;
import reactor.core.publisher.Mono;
import reactor.util.context.Context;

class R2dbcTest {

  private static final Duration T = Duration.ofSeconds(5);
  private final AxizR2dbcEnvironmentPostProcessor epp = new AxizR2dbcEnvironmentPostProcessor();

  @Test
  void poolDefaultsSuitAurora() {
    MockEnvironment env = new MockEnvironment();
    epp.postProcessEnvironment(env, new SpringApplication());
    assertEquals("10m", env.getProperty("spring.r2dbc.pool.max-life-time"));
    assertEquals("remote", env.getProperty("spring.r2dbc.pool.validation-depth"));
    assertEquals("5s", env.getProperty("spring.r2dbc.pool.max-acquire-time"));
    assertNull(env.getProperty("spring.flyway.url"));
  }

  @Test
  void flywayMigratesTheSameDatabaseOverJdbc() {
    MockEnvironment env =
        new MockEnvironment()
            .withProperty("spring.r2dbc.url", "r2dbc:postgresql://db:5432/payments?sslMode=require")
            .withProperty("spring.r2dbc.username", "app")
            .withProperty("spring.r2dbc.password", "local-only");
    epp.postProcessEnvironment(env, new SpringApplication());
    assertEquals(
        "jdbc:postgresql://db:5432/payments?sslmode=require", env.getProperty("spring.flyway.url"));
    assertEquals("app", env.getProperty("spring.flyway.user"));
    assertEquals("local-only", env.getProperty("spring.flyway.password"));

    MockEnvironment explicit =
        new MockEnvironment()
            .withProperty("spring.r2dbc.url", "r2dbc:postgresql://db/payments")
            .withProperty("spring.flyway.url", "jdbc:postgresql://admin-host/payments");
    epp.postProcessEnvironment(explicit, new SpringApplication());
    assertEquals(
        "jdbc:postgresql://admin-host/payments", explicit.getProperty("spring.flyway.url"));
  }

  @Test
  void iamUsesTlsAndNoStoredPassword() {
    MockEnvironment env =
        new MockEnvironment()
            .withProperty("spring.r2dbc.url", "r2dbc:postgresql://db/payments")
            .withProperty("spring.r2dbc.username", "app")
            .withProperty("axiz.r2dbc.iam.enabled", "true");
    epp.postProcessEnvironment(env, new SpringApplication());
    assertEquals("require", env.getProperty("spring.r2dbc.properties.sslMode"));
    assertNull(env.getProperty("spring.flyway.password"));
  }

  @Test
  void iamTokenIsResolvedPerConnection() {
    DatabasePasswordProvider tokens = () -> "token-1";
    new ApplicationContextRunner()
        .withPropertyValues("axiz.r2dbc.encryption-context=account.number")
        .withConfiguration(AutoConfigurations.of(AxizR2dbcIamAutoConfiguration.class))
        .withBean(DatabasePasswordProvider.class, () -> tokens)
        .withPropertyValues("axiz.r2dbc.iam.enabled=true")
        .run(
            ctx -> {
              ConnectionFactoryOptions.Builder b =
                  ConnectionFactoryOptions.parse("r2dbc:postgresql://db/payments").mutate();
              ctx.getBean(ConnectionFactoryOptionsBuilderCustomizer.class).customize(b);
              Object password = b.build().getValue(ConnectionFactoryOptions.PASSWORD);
              assertInstanceOf(org.reactivestreams.Publisher.class, password);
              assertEquals(
                  "token-1",
                  Mono.from((org.reactivestreams.Publisher<?>) password).block(T).toString());
            });
    new ApplicationContextRunner()
        .withPropertyValues("axiz.r2dbc.encryption-context=account.number")
        .withConfiguration(AutoConfigurations.of(AxizR2dbcIamAutoConfiguration.class))
        .run(ctx -> assertFalse(ctx.containsBean("axizR2dbcIamPassword")));
  }

  @Test
  void statementsJoinTheReactiveTransaction() {
    FakeR2dbc db =
        new FakeR2dbc()
            .onUpdate("UPDATE payments", binds -> 1L)
            .onQuery(
                "SELECT status",
                binds -> List.of(Map.of("status", "PAID", "id", String.valueOf(binds.get(0)))));
    context(db)
        .run(
            ctx -> {
              ReactiveSql sql = ctx.getBean(ReactiveSql.class);
              TransactionalOperator tx =
                  TransactionalOperator.create(ctx.getBean(ReactiveTransactionManager.class));
              String status =
                  tx.transactional(
                          sql.update(
                                  "UPDATE payments SET status = :status, error = :error WHERE id = :id",
                                  SqlParams.of()
                                      .bind("status", "PAID")
                                      .bind("error", null, String.class)
                                      .bind("id", "p-1"))
                              .then(
                                  sql.queryOne(
                                      "SELECT status FROM payments WHERE id = :id",
                                      SqlParams.of().bind("id", "p-1"),
                                      r -> r.require("status", String.class))))
                      .block(T);
              assertEquals("PAID", status);
              FakeR2dbc.Execution update = db.last("UPDATE payments");
              assertEquals(
                  "UPDATE payments SET status = $1, error = $2 WHERE id = $3", update.sql());
              // the null error travels as a typed null (Spring binds Parameters.in(String.class))
              assertNull(update.binds().get(1));
              assertEquals(String.class, update.types().get(1));
              assertEquals("p-1", update.binds().get(2));
              assertEquals(update.connection(), db.last("SELECT status").connection());
              assertTrue(db.events().contains("begin:" + update.connection()));
              assertTrue(db.events().contains("commit:" + update.connection()));
            });
  }

  @Test
  void axizColumnTypesHaveConverters() {
    context(new FakeR2dbc())
        .run(
            ctx -> {
              R2dbcCustomConversions conversions = ctx.getBean(R2dbcCustomConversions.class);
              assertEquals(
                  String.class,
                  conversions.getCustomWriteTarget(EncryptedText.class).orElseThrow());
              assertEquals(
                  String.class, conversions.getCustomWriteTarget(Money.class).orElseThrow());
              assertTrue(conversions.hasCustomReadTarget(String.class, Money.class));
            });
  }

  @Test
  @SuppressWarnings("unchecked")
  void encryptedTextIsStoredAsCiphertext() {
    FieldEncryptor enc =
        new FieldEncryptor(new StaticKeyProvider("k1", Map.of("k1", AesGcm.newKey())));
    List<Object> converters = AxizR2dbcConverters.all(() -> enc, "account.number");
    Converter<EncryptedText, String> write = (Converter<EncryptedText, String>) converters.get(0);
    Converter<String, EncryptedText> read = (Converter<String, EncryptedText>) converters.get(1);
    String stored = write.convert(EncryptedText.of("1910012345678"));
    assertNotEquals("1910012345678", stored);
    assertEquals("1910012345678", read.convert(stored).value());
    assertEquals("1910012345678", enc.decrypt(stored, "account.number"));
    Converter<Money, String> money = (Converter<Money, String>) converters.get(2);
    Converter<String, Money> parse = (Converter<String, Money>) converters.get(3);
    Money m = Money.of(new BigDecimal("150.00"), "PEN");
    assertEquals(m, parse.convert(money.convert(m)));
  }

  @Test
  void auditorIsTheCallerOfTheRequest() {
    RequestReactiveAuditor auditor = new RequestReactiveAuditor();
    assertEquals("system", auditor.getCurrentAuditor().block(T));
    RequestMetadata user =
        RequestMetadata.builder("corr-12345678", "req-12345678")
            .clientId("app")
            .subject("user-1")
            .build();
    assertEquals(
        "user-1",
        auditor.getCurrentAuditor().contextWrite(Context.of(RequestContext.KEY, user)).block(T));
    RequestMetadata machine =
        RequestMetadata.builder("corr-12345678", "req-12345678").clientId("batch-app").build();
    try (RequestContext.Scope s = RequestContext.open(machine)) {
      assertEquals("batch-app", auditor.getCurrentAuditor().block(T));
    }
  }

  @Test
  void readerUrlsArePooled() {
    assertEquals(
        "r2dbc:pool:postgresql://ro/db", ReaderConnections.pooled("r2dbc:postgresql://ro/db"));
    assertEquals(
        "r2dbc:pool:postgresql://ro/db", ReaderConnections.pooled("r2dbc:pool:postgresql://ro/db"));
    assertThrows(IllegalArgumentException.class, () -> ReaderConnections.pooled("jdbc:x"));
  }

  private static ApplicationContextRunner context(FakeR2dbc db) {
    return new ApplicationContextRunner()
        .withPropertyValues("axiz.r2dbc.encryption-context=account.number")
        .withConfiguration(AutoConfigurations.of(AxizR2dbcAutoConfiguration.class))
        .withPropertyValues("axiz.r2dbc.encryption-context=account.number")
        .withBean(ConnectionFactory.class, () -> db)
        .withBean(DatabaseClient.class, () -> DatabaseClient.create(db))
        .withBean(ReactiveTransactionManager.class, () -> new R2dbcTransactionManager(db));
  }
}
