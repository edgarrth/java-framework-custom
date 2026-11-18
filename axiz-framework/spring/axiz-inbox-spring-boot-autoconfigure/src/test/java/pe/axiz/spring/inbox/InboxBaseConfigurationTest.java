package pe.axiz.spring.inbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import pe.axiz.core.inbox.AsyncInboxStore;
import pe.axiz.core.inbox.InboxStore;
import pe.axiz.core.messaging.Deduplicator;
import reactor.test.StepVerifier;

class InboxBaseConfigurationTest {
  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(AxizInboxAutoConfiguration.class));

  @Test
  void missingOrIncompatibleStoreFailsBeforeAnyConsumerCanStart() {
    runner.run(context -> assertThat(context).hasFailed());
    var store = mock(InboxStore.class);
    doThrow(new IllegalStateException("missing owner column")).when(store).verifySchema();
    runner.withBean(InboxStore.class, () -> store).run(context -> assertThat(context).hasFailed());
    runner
        .withPropertyValues("axiz.inbox.enabled=false")
        .run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(Deduplicator.class));
  }

  @Test
  void selectedStoreReceivesConfiguredLeaseRetentionAndInjectedClock() {
    var now = Instant.parse("2026-01-01T00:00:00Z");
    var store = mock(InboxStore.class);
    when(store.tryAcquire(
            eq("billing"),
            eq("message"),
            anyString(),
            eq(now),
            eq(now.plusSeconds(120)),
            eq(now.plusSeconds(3600))))
        .thenReturn(InboxStore.Acquisition.processed());
    runner
        .withBean(InboxStore.class, () -> store)
        .withBean(Clock.class, () -> Clock.fixed(now, ZoneOffset.UTC))
        .withPropertyValues("axiz.inbox.lease=2m", "axiz.inbox.retention=1h")
        .run(
            context -> {
              assertThat(context).hasNotFailed().hasSingleBean(Deduplicator.class);
              assertThat(context.getBean(Deduplicator.class).claim("billing", "message").status())
                  .isEqualTo(Deduplicator.Claim.Status.ALREADY_PROCESSED);
              verify(store).verifySchema();
            });
    runner
        .withBean(InboxStore.class, () -> store)
        .withPropertyValues("axiz.inbox.transactional=invalid")
        .run(context -> assertThat(context).hasFailed());
  }

  @Test
  void reactiveApplicationUsesItsAsyncStoreAndHonorsConsumerOverrides() {
    var sync = mock(InboxStore.class);
    var async = mock(AsyncInboxStore.class);
    when(async.release("billing", "message", "owner"))
        .thenReturn(CompletableFuture.completedFuture(true));
    new ReactiveWebApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(AxizInboxAutoConfiguration.class))
        .withBean(InboxStore.class, () -> sync)
        .withBean(AsyncInboxStore.class, () -> async)
        .run(
            context -> {
              assertThat(context).hasNotFailed().hasSingleBean(ReactiveInbox.class);
              StepVerifier.create(
                      context.getBean(ReactiveInbox.class).release("billing", "message", "owner"))
                  .expectNext(true)
                  .verifyComplete();
              verify(async).release("billing", "message", "owner");
            });
    var custom = Deduplicator.none();
    runner
        .withBean(InboxStore.class, () -> sync)
        .withBean(Deduplicator.class, () -> custom)
        .run(context -> assertThat(context.getBean(Deduplicator.class)).isSameAs(custom));
  }
}
