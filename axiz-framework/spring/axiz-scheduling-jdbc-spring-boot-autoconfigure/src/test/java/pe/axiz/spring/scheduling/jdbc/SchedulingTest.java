package pe.axiz.spring.scheduling.jdbc;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import javax.sql.DataSource;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.SimpleLock;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import pe.axiz.spring.scheduling.AxizSchedulingAutoConfiguration;

class SchedulingTest {

  @Test
  void onlyOneHolderPerTask() {
    JdbcDataSource h2 = new JdbcDataSource();
    h2.setURL("jdbc:h2:mem:custom-shedlock;DB_CLOSE_DELAY=-1");
    new ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                AxizSchedulingAutoConfiguration.class, AxizSchedulingJdbcAutoConfiguration.class))
        .withBean(DataSource.class, () -> h2)
        .withPropertyValues(
            "axiz.scheduling.table=jobs_lock", "axiz.scheduling.initialize-schema=ALWAYS")
        .run(
            ctx -> {
              LockProvider provider = ctx.getBean(LockProvider.class);
              LockConfiguration cfg =
                  new LockConfiguration(
                      Instant.now(),
                      "nightly-reconciliation",
                      Duration.ofMinutes(5),
                      Duration.ZERO);
              Optional<SimpleLock> first = provider.lock(cfg);
              assertTrue(first.isPresent());
              assertFalse(provider.lock(cfg).isPresent());
              first.get().unlock();
              assertTrue(provider.lock(cfg).isPresent());
            });
  }
}
