package pe.axiz.spring.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.micrometer.core.instrument.binder.MeterBinder;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.kafka.clients.producer.MockProducer;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.aop.Advisor;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.http.HttpStatus;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.util.pattern.PathPatternParser;
import pe.axiz.core.audit.AsyncAuditPublisher;
import pe.axiz.core.audit.AuditEvent;
import pe.axiz.core.audit.AuditPolicy;
import pe.axiz.core.audit.AuditPublisher;
import pe.axiz.core.audit.AuditTrail;
import pe.axiz.core.audit.Auditable;
import pe.axiz.core.audit.ConfirmedAuditPublisher;
import pe.axiz.core.commons.RequestContext;
import pe.axiz.core.commons.RequestMetadata;
import pe.axiz.core.outbox.OutboxStore;
import pe.axiz.core.outbox.OutboxWriter;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Which events {@code @Auditable} and the HTTP audit record, and how they are delivered. */
class AuditBehaviourTest {

  private final List<AuditEvent> events = new CopyOnWriteArrayList<>();

  private ApplicationContextRunner sync() {
    return new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(AxizAuditAutoConfiguration.class))
        .withPropertyValues("spring.application.name=payments", "axiz.audit.async.enabled=false")
        .withBean(AuditSink.class, () -> new AuditSink("memory", events::add));
  }

  public static class Accounts {
    @Auditable(action = "account.block", resource = "account", policy = AuditPolicy.ON_FAILURE)
    public void block(String id, boolean fail) {
      if (fail) {
        throw new IllegalStateException("core down");
      }
    }

    @Auditable(
        action = "account.read",
        resource = "account",
        captureArguments = false,
        captureResult = true)
    public String read(String id) {
      return "balance-of-" + id;
    }

    @Auditable(action = "account.async", resource = "account")
    public Mono<String> async(boolean fail) {
      return fail ? Mono.error(new IllegalArgumentException("bad")) : Mono.just("ok");
    }

    @Auditable(action = "account.lines", resource = "account")
    public Flux<Integer> lines() {
      return Flux.just(1, 2);
    }

    public String plain() {
      return "plain";
    }
  }

  private static Accounts proxy(Advisor advisor) {
    ProxyFactory pf = new ProxyFactory(new Accounts());
    pf.setProxyTargetClass(true);
    pf.addAdvisor(advisor);
    return (Accounts) pf.getProxy();
  }

  @Test
  void policiesCaptureAndReactiveResultsDecideWhatIsRecorded() {
    sync()
        .run(
            ctx -> {
              Accounts a = proxy(ctx.getBean("axizAuditableAdvisor", Advisor.class));
              a.block("A-1", false);
              assertThrows(IllegalStateException.class, () -> a.block("A-1", true));
              assertEquals("balance-of-A-2", a.read("A-2"));
              assertEquals("ok", a.async(false).block());
              assertThrows(IllegalArgumentException.class, () -> a.async(true).block());
              assertEquals(List.of(1, 2), a.lines().collectList().block());

              ProxyFactory direct = new ProxyFactory(new Accounts());
              direct.setProxyTargetClass(true);
              direct.addAdvice(
                  new AuditableInterceptor(
                      () -> ctx.getBean(AuditTrail.class),
                      AuditContextResolver::fromRequestContext));
              assertEquals("plain", ((Accounts) direct.getProxy()).plain());
            });
    assertThat(events.stream().map(e -> e.action() + ":" + e.outcome()))
        .containsExactly(
            "account.block:FAILURE",
            "account.read:SUCCESS",
            "account.async:SUCCESS",
            "account.async:FAILURE",
            "account.lines:SUCCESS");
    AuditEvent blocked = events.get(0);
    assertEquals("IllegalStateException", blocked.errorCode());
    AuditEvent read = events.get(1);
    assertThat(read.details()).doesNotContainKey("arguments").containsKey("result");
  }

  @Test
  void eventsGoToEverySinkOrToTheLogAndAsyncDeliveryIsTheDefault() {
    List<AuditEvent> second = new CopyOnWriteArrayList<>();
    sync()
        .withBean("second", AuditSink.class, () -> new AuditSink("second", second::add))
        .run(
            ctx -> {
              assertThat(ctx.getBean(AuditPublisher.class))
                  .isInstanceOf(ConfirmedAuditPublisher.class);
              ctx.getBean(AuditTrail.class).record("probe", "test").success();
            });
    assertEquals(1, events.size());
    assertEquals(1, second.size());

    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(AxizAuditAutoConfiguration.class))
        .run(
            ctx -> {
              AuditPublisher publisher = ctx.getBean(AuditPublisher.class);
              assertThat(publisher).isInstanceOf(AsyncAuditPublisher.class);
              SimpleMeterRegistry registry = new SimpleMeterRegistry();
              ctx.getBean(MeterBinder.class).bindTo(registry);
              assertTrue(registry.find("axiz.audit.pending").gauge() != null);
              assertTrue(registry.find("axiz.audit.lost").functionCounter() != null);
              assertEquals(1.0, registry.get("axiz.audit.worker.alive").gauge().value());
            });

    sync()
        .run(
            ctx -> {
              SimpleMeterRegistry registry = new SimpleMeterRegistry();
              ctx.getBean(MeterBinder.class).bindTo(registry);
              assertNull(registry.find("axiz.audit.pending").gauge(), "no queue, no queue metrics");
              assertNotNull(registry.find("axiz.audit.persistence.unconfirmed").functionCounter());
            });
  }

  private static OutboxStore outbox() {
    return (OutboxStore)
        Proxy.newProxyInstance(
            OutboxStore.class.getClassLoader(),
            new Class<?>[] {OutboxStore.class},
            (proxy, method, args) -> null);
  }

  @Test
  void durableAuditNeedsTheOutboxAndKeepsTheQueueAsFallback() {
    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(AxizAuditAutoConfiguration.class))
        .withPropertyValues("axiz.audit.durable.enabled=true")
        .run(
            ctx ->
                assertThat(ctx)
                    .hasFailed()
                    .getFailure()
                    .hasStackTraceContaining("needs the outbox capability"));

    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(AxizAuditAutoConfiguration.class))
        .withPropertyValues(
            "axiz.audit.durable.enabled=true",
            "axiz.audit.durable.destination=audit-events",
            "axiz.audit.durable.outside-transaction=fail")
        .withBean(OutboxWriter.class, () -> new OutboxWriter(outbox(), Clock.systemUTC()))
        .withBean(
            PlatformTransactionManager.class, () -> Mockito.mock(PlatformTransactionManager.class))
        .run(
            ctx -> {
              AuditPublisher publisher = ctx.getBean(AuditPublisher.class);
              assertThat(publisher).isInstanceOf(TransactionalAuditPublisher.class);
              assertThat(((TransactionalAuditPublisher) publisher).fallback())
                  .isInstanceOf(AsyncAuditPublisher.class);
              SimpleMeterRegistry registry = new SimpleMeterRegistry();
              ctx.getBean(MeterBinder.class).bindTo(registry);
              assertTrue(registry.find("axiz.audit.pending").gauge() != null);
            });
  }

  @Test
  void closingTheDurablePublisherClosesItsFallback() throws Exception {
    AtomicBoolean closed = new AtomicBoolean();
    class ClosingPublisher implements AuditPublisher, AutoCloseable {
      @Override
      public void publish(AuditEvent event) {}

      @Override
      public void close() {
        closed.set(true);
      }
    }
    new TransactionalAuditPublisher(
            new OutboxWriter(outbox(), Clock.systemUTC()),
            "audit",
            new ClosingPublisher(),
            AuditOutsideTransaction.ASYNC)
        .close();
    assertTrue(closed.get());
  }

  @Test
  void theContextComesFromTheRequestOrFallsBackToTheCallerAddress() {
    AuditContextResolver resolver = AuditContextResolver.fromRequestContext();
    assertEquals(AuditTrail.Context.system(), resolver.current(null));
    AuditTrail.Context anonymous = resolver.current("10.0.0.5");
    assertEquals("10.0.0.5", anonymous.actor().ip());
    assertNull(anonymous.actor().subject());
    RequestMetadata m =
        RequestMetadata.builder("corr-12345678", "req-12345678")
            .subject("user-1")
            .clientId("web-app")
            .channel("web")
            .tenantId("bank-a")
            .build();
    try (RequestContext.Scope ignored = RequestContext.open(m)) {
      AuditTrail.Context ctx = resolver.current("10.0.0.6");
      assertEquals("user-1", ctx.actor().subject());
      assertEquals("web-app", ctx.actor().clientId());
      assertEquals("corr-12345678", ctx.correlationId());
      assertEquals("bank-a", ctx.tenant());
    }
  }

  @Test
  void theServletAuditRecordsChangesByRoutePatternAndStatus() throws Exception {
    new WebApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(AxizAuditAutoConfiguration.class))
        .withPropertyValues("axiz.audit.async.enabled=false", "axiz.audit.http.enabled=true")
        .withBean(AuditSink.class, () -> new AuditSink("memory", events::add))
        .run(
            ctx -> {
              HttpAuditFilter filter = ctx.getBean(HttpAuditFilter.class);
              MockHttpServletRequest created = new MockHttpServletRequest("POST", "/payments/P-1");
              created.setAttribute(
                  HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/payments/{id}");
              filter.doFilter(
                  created,
                  new MockHttpServletResponse(),
                  (req, res) -> ((MockHttpServletResponse) res).setStatus(201));

              MockHttpServletResponse conflict = new MockHttpServletResponse();
              filter.doFilter(
                  new MockHttpServletRequest("DELETE", "/payments/P-2"),
                  conflict,
                  (req, res) -> ((MockHttpServletResponse) res).setStatus(409));

              assertThrows(
                  IOException.class,
                  () ->
                      filter.doFilter(
                          new MockHttpServletRequest("PUT", "/payments/P-3"),
                          new MockHttpServletResponse(),
                          (req, res) -> {
                            throw new IOException("client went away");
                          }));

              filter.doFilter(
                  new MockHttpServletRequest("GET", "/payments/P-1"),
                  new MockHttpServletResponse(),
                  (req, res) -> {});
            });
    assertThat(events.stream().map(e -> e.action() + " " + e.resourceType() + " " + e.outcome()))
        .containsExactly(
            "http.post /payments/{id} SUCCESS",
            "http.delete unmatched FAILURE",
            "http.put unmatched FAILURE");
    assertEquals("HTTP-409", events.get(1).errorCode());
  }

  @Test
  void theReactiveAuditRecordsTheCallerAddressAndStatus() {
    new ReactiveWebApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                AxizAuditAutoConfiguration.class, AxizNativeAuditAutoConfiguration.class))
        .withPropertyValues("axiz.audit.async.enabled=false", "axiz.audit.http.enabled=true")
        .withBean(
            AsyncAuditSink.class,
            () ->
                new AsyncAuditSink(
                    "memory",
                    event -> {
                      events.add(event);
                      return CompletableFuture.completedFuture(null);
                    }))
        .run(
            ctx -> {
              HttpAuditWebFilter filter = ctx.getBean(HttpAuditWebFilter.class);
              MockServerWebExchange ok =
                  MockServerWebExchange.from(
                      MockServerHttpRequest.post("/payments")
                          .remoteAddress(new InetSocketAddress("10.0.0.7", 4000)));
              filter
                  .filter(
                      ok,
                      ex -> {
                        // the reactive attribute: its name differs from the servlet one
                        ex.getAttributes()
                            .put(
                                org.springframework.web.reactive.HandlerMapping
                                    .BEST_MATCHING_PATTERN_ATTRIBUTE,
                                PathPatternParser.defaultInstance.parse("/payments"));
                        return Mono.empty();
                      })
                  .block();
              MockServerWebExchange rejected =
                  MockServerWebExchange.from(MockServerHttpRequest.patch("/payments/P-9"));
              filter
                  .filter(
                      rejected,
                      ex -> {
                        ex.getResponse().setStatusCode(HttpStatus.UNPROCESSABLE_CONTENT);
                        return Mono.empty();
                      })
                  .block();
              MockServerWebExchange read =
                  MockServerWebExchange.from(MockServerHttpRequest.get("/payments"));
              filter.filter(read, ex -> Mono.empty()).block();
            });
    assertEquals(2, events.size());
    assertEquals("http.post", events.get(0).action());
    assertEquals("/payments", events.get(0).resourceType());
    assertEquals("10.0.0.7", events.get(0).actor().ip());
    assertEquals(AuditEvent.Outcome.FAILURE, events.get(1).outcome());
    assertEquals("HTTP-422", events.get(1).errorCode());
  }

  @Test
  void kafkaAndSqsSinksAreAddedOnlyWhenConfigured() {
    ProducerFactory<String, String> producers = MockProducer::new;
    new ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                AxizAuditKafkaAutoConfiguration.class,
                AxizAuditSqsAutoConfiguration.class,
                AxizAuditAutoConfiguration.class))
        .withBean(ProducerFactory.class, () -> producers)
        .withPropertyValues(
            "axiz.audit.kafka.topic=audit",
            "axiz.audit.sqs.queue-url=http://localhost:4566/000000000000/audit",
            "axiz.aws.region=us-east-1")
        .run(
            ctx -> {
              assertThat(ctx).hasNotFailed();
              assertThat(ctx.getBeansOfType(AuditSink.class).values().stream().map(AuditSink::name))
                  .containsExactlyInAnyOrder("kafka", "sqs");
            });
    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(AxizAuditAutoConfiguration.class))
        .withPropertyValues("axiz.audit.enabled=false")
        .run(ctx -> assertThat(ctx).doesNotHaveBean(AuditTrail.class));
  }
}
