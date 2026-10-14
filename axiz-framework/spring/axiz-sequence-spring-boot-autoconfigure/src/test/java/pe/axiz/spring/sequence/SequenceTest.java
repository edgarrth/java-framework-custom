package pe.axiz.spring.sequence;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import javax.sql.DataSource;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

class SequenceTest {

  @Test
  void formattedSequencesOnJdbc() {
    JdbcDataSource h2 = new JdbcDataSource();
    h2.setURL("jdbc:h2:mem:seq;DB_CLOSE_DELAY=-1");
    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(AxizSequenceAutoConfiguration.class))
        .withBean(DataSource.class, () -> h2)
        .withBean(PlatformTransactionManager.class, () -> new DataSourceTransactionManager(h2))
        .withPropertyValues(
            "axiz.sequence.sequences.operation.prefix=OP",
            "axiz.sequence.sequences.operation.width=6")
        .run(
            ctx -> {
              Sequences s = ctx.getBean(Sequences.class);
              String first = s.next("operation");
              String second = s.next("operation");
              assertTrue(first.startsWith("OP"), first);
              assertTrue(first.endsWith("000001"), first);
              assertTrue(second.endsWith("000002"), second);
              assertThrows(IllegalArgumentException.class, () -> s.next("unknown"));
            });
  }
}
