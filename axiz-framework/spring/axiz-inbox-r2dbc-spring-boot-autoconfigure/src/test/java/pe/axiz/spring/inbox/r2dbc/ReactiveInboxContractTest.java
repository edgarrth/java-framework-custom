package pe.axiz.spring.inbox.r2dbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import pe.axiz.adapter.inbox.r2dbc.ReactiveInboxStore;
import pe.axiz.core.inbox.InboxStore.Acquisition;
import reactor.core.publisher.Mono;

class ReactiveInboxContractTest {
  @Test
  void eachOperationIsLazyAndPreservesItsOwnerResultAndTransactionResource() {
    var store = mock(ReactiveInboxStore.class);
    var now = Instant.parse("2026-01-01T00:00:00Z");
    var lease = now.plusSeconds(30);
    var expiry = now.plusSeconds(60);
    var facade = new R2dbcInboxFacade(store);
    var pending = facade.tryAcquire("consumer", "message", "owner", now, lease, expiry);
    verifyNoInteractions(store);
    when(store.tryAcquire("consumer", "message", "owner", now, lease, expiry))
        .thenReturn(Mono.just(Acquisition.acquired()));
    assertThat(pending.block()).isEqualTo(Acquisition.acquired());
    when(store.markProcessed("consumer", "message", "owner", now)).thenReturn(Mono.just(false));
    when(store.release("consumer", "message", "owner")).thenReturn(Mono.just(true));
    when(store.purgeExpired(now)).thenReturn(Mono.just(3));
    when(store.verifySchema()).thenReturn(Mono.empty());
    assertThat(facade.markProcessed("consumer", "message", "owner", now).block()).isFalse();
    assertThat(facade.release("consumer", "message", "owner").block()).isTrue();
    assertThat(facade.purgeExpired(now).block()).isEqualTo(3);
    facade.verifySchema().block();
    verify(store).verifySchema();
    var beans = new DefaultListableBeanFactory();
    beans.registerSingleton("clock", Clock.fixed(now, ZoneOffset.UTC));
    var config = new AxizInboxR2dbcAutoConfiguration();
    assertThat(config.axizR2dbcInboxCompletion(store, beans.getBeanProvider(Clock.class))).isNull();
    var resource = new Object();
    when(store.transactionResource()).thenReturn(resource);
    var completion = config.axizR2dbcInboxCompletion(store, beans.getBeanProvider(Clock.class));
    assertThat(completion.transactionResource()).isSameAs(resource);
    assertThat(completion.complete("consumer", "message", "owner").block()).isFalse();
  }
}
