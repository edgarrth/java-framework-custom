package pe.axiz.spring.tenancy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.hibernate.autoconfigure.HibernatePropertiesCustomizer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import pe.axiz.core.context.RequestContext;
import pe.axiz.core.context.RequestMetadata;
import pe.axiz.core.errors.ValidationException;
import pe.axiz.core.tenancy.TenantContext;
import reactor.core.publisher.Mono;

/** Cómo se vincula, se limpia y se propaga el tenant en ambas pilas web. */
class TenantBindingTest {

  private static TenantFilterSupport support(boolean trustHeader, boolean required) {
    return new TenantFilterSupport(
        new AxizTenancyProperties(
            true, "X-Tenant-Id", trustHeader, required, List.of("/actuator/**")));
  }

  private static RequestMetadata metadata(String tenant) {
    return RequestMetadata.builder("corr-12345678", "req-12345678").tenantId(tenant).build();
  }

  @Test
  void theServletFilterPublishesTheTenantToLogsAndCleansUpAfterwards() throws Exception {
    TenantFilter filter = new TenantFilter(support(false, true));
    AtomicReference<String> mdc = new AtomicReference<>();
    AtomicReference<String> contextTenant = new AtomicReference<>();
    try (RequestContext.Scope ignored = RequestContext.open(metadata("bank-a"))) {
      filter.doFilter(
          new MockHttpServletRequest("GET", "/accounts"),
          new MockHttpServletResponse(),
          (rq, rs) -> {
            mdc.set(MDC.get("tenant"));
            contextTenant.set(RequestContext.get().tenantId());
          });
    }
    assertEquals("bank-a", contextTenant.get());
    if (mdc.get() != null) { // MDC no hace nada sin un binding de SLF4J
      assertEquals("bank-a", mdc.get());
    }
    assertNull(MDC.get("tenant"));
    assertTrue(TenantContext.current().isEmpty());
  }

  @Test
  void theServletFilterCleansUpEvenWhenTheChainFails() {
    TenantFilter filter = new TenantFilter(support(true, true));
    MockHttpServletRequest req = new MockHttpServletRequest("GET", "/accounts");
    req.addHeader("X-Tenant-Id", "bank-b");
    try {
      filter.doFilter(
          req,
          new MockHttpServletResponse(),
          (rq, rs) -> {
            throw new IllegalStateException("downstream");
          });
    } catch (Exception expected) {
      assertEquals("downstream", expected.getMessage());
    }
    assertTrue(TenantContext.current().isEmpty());
    assertNull(MDC.get("tenant"));
  }

  @Test
  void anOptionalTenantLetsTheRequestThroughUnbound() throws Exception {
    TenantFilter filter = new TenantFilter(support(false, false));
    AtomicReference<Boolean> bound = new AtomicReference<>();
    filter.doFilter(
        new MockHttpServletRequest("GET", "/catalog"),
        new MockHttpServletResponse(),
        (rq, rs) -> bound.set(TenantContext.current().isPresent()));
    assertEquals(Boolean.FALSE, bound.get());
  }

  @Test
  void excludedPathsSkipTheServletFilterEvenWhenATenantIsRequired() throws Exception {
    TenantFilter filter = new TenantFilter(support(false, true));
    MockHttpServletRequest health = new MockHttpServletRequest("GET", "/svc/actuator/health");
    health.setContextPath("/svc");
    AtomicReference<Boolean> reached = new AtomicReference<>(false);
    filter.doFilter(health, new MockHttpServletResponse(), (rq, rs) -> reached.set(true));
    assertTrue(reached.get());
  }

  @Test
  void theReactiveFilterPutsTheTenantInTheReactorContext() {
    TenantWebFilter filter = new TenantWebFilter(support(false, true));
    MockServerWebExchange exchange =
        MockServerWebExchange.from(MockServerHttpRequest.get("/accounts"));
    exchange.getAttributes().put(RequestContext.KEY, metadata("bank-a"));
    AtomicReference<String> seen = new AtomicReference<>();
    AtomicReference<RequestMetadata> enriched = new AtomicReference<>();
    WebFilterChain chain =
        ex ->
            Mono.deferContextual(
                    ctx -> {
                      seen.set(ctx.get(TenantContextAccessor.KEY));
                      enriched.set(ctx.get(RequestContext.KEY));
                      return Mono.<Void>empty();
                    })
                .then();
    filter.filter(exchange, chain).block();
    assertEquals("bank-a", seen.get());
    assertEquals("bank-a", enriched.get().tenantId());
    RequestMetadata stored = exchange.getAttribute(RequestContext.KEY);
    assertEquals("bank-a", stored.tenantId());
  }

  @Test
  void theReactiveFilterTrustsTheHeaderOnlyWhenConfigured() {
    MockServerWebExchange exchange =
        MockServerWebExchange.from(
            MockServerHttpRequest.get("/accounts").header("X-Tenant-Id", "bank-b"));
    AtomicReference<String> seen = new AtomicReference<>();
    WebFilterChain chain =
        ex ->
            Mono.deferContextual(
                ctx -> {
                  seen.set(ctx.getOrDefault(TenantContextAccessor.KEY, "none"));
                  return Mono.<Void>empty();
                });
    new TenantWebFilter(support(true, true)).filter(exchange, chain).block();
    assertEquals("bank-b", seen.get());

    Mono<Void> rejected = new TenantWebFilter(support(false, true)).filter(exchange, chain);
    assertThrows(ValidationException.class, rejected::block);
    new TenantWebFilter(support(false, false)).filter(exchange, chain).block();
    assertEquals("none", seen.get());
  }

  @Test
  void theReactiveFilterSkipsExcludedPaths() {
    MockServerWebExchange exchange =
        MockServerWebExchange.from(MockServerHttpRequest.get("/actuator/health"));
    AtomicReference<Boolean> reached = new AtomicReference<>(false);
    new TenantWebFilter(support(false, true))
        .filter(
            exchange,
            ex -> {
              reached.set(true);
              return Mono.empty();
            })
        .block();
    assertTrue(reached.get());
  }

  @Test
  void theContextAccessorCarriesTheTenantAcrossThreads() {
    TenantContextAccessor accessor = new TenantContextAccessor();
    assertEquals(TenantContextAccessor.KEY, accessor.key());
    assertNull(accessor.getValue());
    accessor.setValue("bank-a");
    try {
      assertEquals("bank-a", accessor.getValue());
      assertEquals("bank-a", TenantContext.require());
    } finally {
      accessor.setValue();
    }
    assertTrue(TenantContext.current().isEmpty());
  }

  @Test
  void hibernateSeesTheCurrentTenantOrTheSystemOne() {
    TenantIdentifierResolver resolver = new TenantIdentifierResolver();
    assertEquals(TenantIdentifierResolver.SYSTEM, resolver.resolveCurrentTenantIdentifier());
    assertTrue(resolver.validateExistingCurrentSessions());
    TenantContext.restore("bank-a");
    try {
      assertEquals("bank-a", resolver.resolveCurrentTenantIdentifier());
    } finally {
      TenantContext.restore(null);
    }
  }

  @Test
  void tenancyIsOffUnlessTheServiceDeclaresIt() {
    new ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                AxizTenancyAutoConfiguration.class, AxizTenancyHibernateAutoConfiguration.class))
        .run(
            ctx ->
                assertThat(ctx)
                    .doesNotHaveBean(TenantFilterSupport.class)
                    .doesNotHaveBean(HibernatePropertiesCustomizer.class));
  }

  @Test
  void aServletServiceGetsTheFilterAfterSecurityAndHibernateGetsTheResolver() {
    new WebApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                AxizTenancyAutoConfiguration.class, AxizTenancyHibernateAutoConfiguration.class))
        .withPropertyValues("axiz.tenancy.enabled=true")
        .run(
            ctx -> {
              assertThat(ctx).hasNotFailed().doesNotHaveBean(TenantWebFilter.class);
              FilterRegistrationBean<?> registration =
                  ctx.getBean("axizTenantFilter", FilterRegistrationBean.class);
              assertEquals(AxizTenancyAutoConfiguration.FILTER_ORDER, registration.getOrder());
              assertThat(registration.getFilter()).isInstanceOf(TenantFilter.class);
              Map<String, Object> props = new HashMap<>();
              ctx.getBean(HibernatePropertiesCustomizer.class).customize(props);
              assertThat(props.get("hibernate.tenant_identifier_resolver"))
                  .isInstanceOf(TenantIdentifierResolver.class);
            });
  }

  @Test
  void aReactiveServiceGetsTheWebFilter() {
    new ReactiveWebApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(AxizTenancyAutoConfiguration.class))
        .withPropertyValues("axiz.tenancy.enabled=true", "axiz.tenancy.trust-header=true")
        .run(
            ctx -> {
              assertThat(ctx).hasNotFailed().hasSingleBean(TenantWebFilter.class);
              assertThat(ctx).doesNotHaveBean("axizTenantFilter");
              assertTrue(
                  ctx.getBean(TenantFilterSupport.class).resolve(null, h -> "bank-c").isPresent());
            });
  }
}
