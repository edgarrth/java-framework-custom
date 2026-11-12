package pe.axiz.spring.scheduling;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import io.r2dbc.spi.ConnectionFactory;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.r2dbc.R2dbcLockProvider;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import pe.axiz.spring.foundation.aws.AxizAwsIdentityAutoConfiguration;
import pe.axiz.test.support.r2dbc.FakeR2dbc;

class SchedulingR2dbcTest {

  @Test
  void reactiveServicesLockOverR2dbc() {
    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(AxizAwsIdentityAutoConfiguration.class))
        .withConfiguration(
            AutoConfigurations.of(
                AxizSchedulingR2dbcAutoConfiguration.class, AxizSchedulingAutoConfiguration.class))
        .withPropertyValues("axiz.scheduling.store=r2dbc", "axiz.scheduling.table=jobs_lock")
        .withBean(ConnectionFactory.class, FakeR2dbc::new)
        .run(
            ctx -> {
              assertInstanceOf(R2dbcLockProvider.class, ctx.getBean(LockProvider.class));
              assertEquals(1, ctx.getBeansOfType(LockProvider.class).size());
            });
  }
}
