package pe.axiz.test.contracts;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Statement;
import java.util.UUID;
import java.util.stream.Stream;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import pe.axiz.adapter.inbox.jdbc.JdbcInboxStore;
import pe.axiz.core.inbox.InboxStore;
import pe.axiz.kit.jdbc.JdbcTransactions;

/** The JDBC inbox lives in the API module, so its contract runs here (on H2). */
class JdbcInboxStoreContractTest {

  static InboxStore h2() {
    try {
      JdbcDataSource ds = new JdbcDataSource();
      ds.setURL("jdbc:h2:mem:inbox-" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
      try (Connection c = ds.getConnection();
          Statement s = c.createStatement();
          InputStream in = JdbcInboxStore.class.getResourceAsStream("/axiz/sql/inbox-h2.sql")) {
        for (String sql : new String(in.readAllBytes(), StandardCharsets.UTF_8).split(";")) {
          if (!sql.replaceAll("--.*", "").isBlank()) {
            s.execute(sql);
          }
        }
      }
      return new JdbcInboxStore(new JdbcTransactions(ds), "axiz_inbox");
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  @TestFactory
  Stream<DynamicTest> contract() {
    return InboxStoreContract.checks(JdbcInboxStoreContractTest::h2).stream()
        .map(c -> DynamicTest.dynamicTest(c.name(), c::run));
  }
}
