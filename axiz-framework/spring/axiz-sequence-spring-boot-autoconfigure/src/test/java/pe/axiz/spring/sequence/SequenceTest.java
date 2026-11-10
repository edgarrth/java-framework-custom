package pe.axiz.spring.sequence;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import javax.sql.DataSource;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import pe.axiz.core.sequence.SequenceStore;

class SequenceTest {

  @Test
  void businessRollbackCannotReturnAReservedBlockToAnotherReplica() {
    JdbcDataSource h2 = new JdbcDataSource();
    h2.setURL("jdbc:h2:mem:seq-rollback;DB_CLOSE_DELAY=-1");
    DataSourceTransactionManager transactions = new DataSourceTransactionManager(h2);
    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(AxizSequenceAutoConfiguration.class))
        .withBean(DataSource.class, () -> h2)
        .withBean(PlatformTransactionManager.class, () -> transactions)
        .withPropertyValues("axiz.sequence.sequences.operation.block-size=10")
        .run(
            context -> {
              Sequences firstReplica = context.getBean(Sequences.class);
              TransactionTemplate business = new TransactionTemplate(transactions);
              assertThrows(
                  IllegalStateException.class,
                  () ->
                      business.execute(
                          status -> {
                            firstReplica.next("operation");
                            throw new IllegalStateException("rollback business only");
                          }));
              Sequences secondReplica =
                  new Sequences(
                      context.getBean(AxizSequenceProperties.class),
                      context.getBean(SequenceStore.class),
                      Clock.systemUTC());
              Assertions.assertEquals("00000002", firstReplica.next("operation"));
              Assertions.assertEquals("00000011", secondReplica.next("operation"));
            });
  }

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
