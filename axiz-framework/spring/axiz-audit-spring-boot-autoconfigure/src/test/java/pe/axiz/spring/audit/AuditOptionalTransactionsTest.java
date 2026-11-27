package pe.axiz.spring.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.aop.AopAutoConfiguration;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import pe.axiz.core.audit.AuditEvent;
import pe.axiz.core.audit.Auditable;
import pe.axiz.core.commons.TransactionOutcome;
import reactor.core.publisher.Mono;

class AuditOptionalTransactionsTest {
  public static class Work {
    @Auditable(action = "operation", resource = "test")
    public String execute() {
      return "done";
    }
  }

  public static class NativeWork {
    @Auditable(action = "operation", resource = "test")
    public Mono<String> execute() {
      return Mono.just("done");
    }
  }

  @Test
  void imperativeAuditWorksWhenTheTransactionCapabilityIsAbsent() {
    var events = new ArrayList<AuditEvent>();
    new ApplicationContextRunner()
        .withPropertyValues("axiz.config.environment=test")
        .withClassLoader(new FilteredClassLoader("org.springframework.transaction"))
        .withConfiguration(
            AutoConfigurations.of(AopAutoConfiguration.class, AxizAuditAutoConfiguration.class))
        .withPropertyValues("axiz.audit.mode=sync")
        .withBean(Work.class, Work::new)
        .withBean(AuditSink.class, () -> new AuditSink("test", events::add))
        .run(
            ctx -> {
              assertThat(ctx).hasNotFailed();
              assertThat(ctx).doesNotHaveBean(AuditAdvisorOrderValidator.class);
              assertEquals("done", ctx.getBean(Work.class).execute());
              assertEquals(1, events.size());
              assertEquals(TransactionOutcome.UNKNOWN, events.getFirst().transactionOutcome());
            });
  }

  @Test
  void nativeAuditWorksWhenTheTransactionCapabilityIsAbsent() {
    var events = new ArrayList<AuditEvent>();
    new ReactiveWebApplicationContextRunner()
        .withPropertyValues("axiz.config.environment=test")
        .withClassLoader(new FilteredClassLoader("org.springframework.transaction"))
        .withConfiguration(
            AutoConfigurations.of(
                AopAutoConfiguration.class,
                AxizAuditAutoConfiguration.class,
                AxizNativeAuditAutoConfiguration.class))
        .withPropertyValues("axiz.audit.mode=sync")
        .withBean(NativeWork.class, NativeWork::new)
        .withBean(
            AsyncAuditSink.class,
            () ->
                new AsyncAuditSink(
                    "test",
                    event -> {
                      events.add(event);
                      return CompletableFuture.completedFuture(null);
                    }))
        .run(
            ctx -> {
              assertThat(ctx).hasNotFailed();
              assertThat(ctx).doesNotHaveBean(AuditAdvisorOrderValidator.class);
              assertEquals("done", ctx.getBean(NativeWork.class).execute().block());
              assertEquals(1, events.size());
              assertEquals(TransactionOutcome.UNKNOWN, events.getFirst().transactionOutcome());
            });
  }
}
