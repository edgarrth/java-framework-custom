package pe.axiz.spring.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.core.MethodParameter;
import org.springframework.core.Ordered;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.servlet.config.annotation.ApiVersionConfigurer;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import pe.axiz.core.api.HeaderCatalog;
import pe.axiz.core.api.HeaderDefinition;
import pe.axiz.core.api.PageRequest;
import pe.axiz.core.api.Sort;
import pe.axiz.core.context.RequestContext;
import pe.axiz.core.context.RequestMetadata;
import pe.axiz.core.errors.ValidationException;
import reactor.core.publisher.Mono;
import tools.jackson.databind.json.JsonMapper;

/** Reglas de paginación, CORS, versiones de la API, JSON y el contexto de solicitud reactivo. */
class WebConventionsTest {

  private static AxizWebProperties properties(String... pairs) {
    return new Binder(new MapConfigurationPropertySource(toMap(pairs)))
        .bindOrCreate("axiz.web", AxizWebProperties.class);
  }

  private static Map<String, String> toMap(String... pairs) {
    Map<String, String> m = new LinkedHashMap<>();
    for (String p : pairs) {
      int i = p.indexOf('=');
      m.put(p.substring(0, i), p.substring(i + 1));
    }
    return m;
  }

  @SuppressWarnings("unused")
  private void statements(
      @Paging(
              sortable = {"bookedAt", "amount"},
              defaultSort = {"bookedAt,desc"},
              defaultSize = 50)
          PageRequest page) {}

  private static Paging rules() throws NoSuchMethodException {
    Method m = WebConventionsTest.class.getDeclaredMethod("statements", PageRequest.class);
    return new MethodParameter(m, 0).getParameterAnnotation(Paging.class);
  }

  @Test
  void anEndpointRestrictsSortingAndChoosesItsDefaults() throws Exception {
    PageRequestParser parser = new PageRequestParser(20, 100);
    Paging rules = rules();
    PageRequest defaults = parser.parse(null, " ", null, rules);
    assertEquals(0, defaults.page());
    assertEquals(50, defaults.size());
    assertEquals("bookedAt", defaults.sort().orders().get(0).field());
    assertEquals(Sort.Direction.DESC, defaults.sort().orders().get(0).direction());

    ValidationException notSortable =
        assertThrows(
            ValidationException.class,
            () -> parser.parse("0", "10", List.of("customerName"), rules));
    assertEquals("Sortable", notSortable.violations().get(0).code());

    ValidationException bounds =
        assertThrows(ValidationException.class, () -> parser.parse("-1", "101", null, null));
    assertEquals(
        List.of("page", "size"), bounds.violations().stream().map(v -> v.field()).toList());
    assertEquals(
        PageRequest.MAX_SIZE,
        new PageRequestParser(10, Integer.MAX_VALUE)
            .parse(null, String.valueOf(PageRequest.MAX_SIZE), null, null)
            .size());
  }

  /** Expone los mapeos configurados (protegidos en Spring). */
  static final class InspectableCors extends CorsRegistry {
    Map<String, CorsConfiguration> configurations() {
      return getCorsConfigurations();
    }
  }

  @Test
  void corsIsClosedUnlessOriginsAreListed() {
    PageRequestParser parser = new PageRequestParser(20, 100);
    InspectableCors closed = new InspectableCors();
    new AxizWebMvcConfigurer(properties(), parser).addCorsMappings(closed);
    assertTrue(closed.configurations().isEmpty());

    InspectableCors open = new InspectableCors();
    new AxizWebMvcConfigurer(
            properties(
                "axiz.web.cors.enabled=true",
                "axiz.web.cors.path-pattern=/v1/**",
                "axiz.web.cors.allowed-origins=https://app.bank.example",
                "axiz.web.cors.max-age=10m"),
            parser)
        .addCorsMappings(open);
    CorsConfiguration c = open.configurations().get("/v1/**");
    assertEquals(List.of("https://app.bank.example"), c.getAllowedOrigins());
    assertEquals(Boolean.FALSE, c.getAllowCredentials());
    assertEquals(600L, c.getMaxAge());
    assertThat(c.getExposedHeaders()).contains("X-Correlation-Id");
    assertNotNull(c.checkOrigin("https://app.bank.example"));
    assertNull(c.checkOrigin("https://evil.example"));
  }

  @Test
  void programmaticCorsSnapshotsItsInputsAndAppliesOnlyTheDeclaredOriginsAndMethods() {
    var defaults = properties();
    var origins = new ArrayList<>(List.of("https://app.example.test"));
    var corsBuilder =
        AxizWebProperties.Cors.builder()
            .enabled(true)
            .pathPattern("/v1/**")
            .allowedOrigins(origins)
            .allowedOriginPatterns(List.of())
            .allowedMethods(List.of("GET"))
            .allowedHeaders(List.of("X-Trace"))
            .exposedHeaders(List.of("X-Correlation-Id"))
            .allowCredentials(true)
            .maxAge(Duration.ofSeconds(90));
    origins.clear();
    var declared =
        AxizWebProperties.builder()
            .headers(defaults.headers())
            .excludedPaths(defaults.excludedPaths())
            .channelHeader(defaults.channelHeader())
            .pagination(defaults.pagination())
            .apiVersion(defaults.apiVersion())
            .cors(corsBuilder.build())
            .json(defaults.json())
            .maxRequestBody(defaults.maxRequestBody())
            .bodyLimits(defaults.bodyLimits())
            .build();
    var registry = new InspectableCors();
    new AxizWebMvcConfigurer(declared, new PageRequestParser(20, 100)).addCorsMappings(registry);
    var effective = registry.configurations().get("/v1/**");
    assertEquals("https://app.example.test", effective.checkOrigin("https://app.example.test"));
    assertNull(effective.checkOrigin("https://evil.example.test"));
    assertEquals(List.of("GET"), effective.getAllowedMethods());
    assertEquals(List.of("X-Trace"), effective.getAllowedHeaders());
    assertEquals(List.of("X-Correlation-Id"), effective.getExposedHeaders());
    assertEquals(Boolean.TRUE, effective.getAllowCredentials());
    assertEquals(90L, effective.getMaxAge());
  }

  /** Expone la estrategia resultante (protegida en Spring). */
  static final class InspectableVersions extends ApiVersionConfigurer {
    org.springframework.web.accept.ApiVersionStrategy strategy() {
      return getApiVersionStrategy();
    }
  }

  @Test
  void apiVersionsComeFromTheHeaderWithTheOldestSupportedAsDefault() {
    PageRequestParser parser = new PageRequestParser(20, 100);
    InspectableVersions off = new InspectableVersions();
    new AxizWebMvcConfigurer(properties(), parser).configureApiVersioning(off);
    assertNull(off.strategy());

    InspectableVersions on = new InspectableVersions();
    new AxizWebMvcConfigurer(
            properties(
                "axiz.web.api-version.enabled=true",
                "axiz.web.api-version.supported[0]=2",
                "axiz.web.api-version.supported[1]=1.1"),
            parser)
        .configureApiVersioning(on);
    org.springframework.web.accept.ApiVersionStrategy strategy = on.strategy();
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v1/accounts");
    request.addHeader("API-Version", "2");
    assertEquals("2", strategy.resolveVersion(request));
    assertEquals(
        String.valueOf(strategy.parseVersion("1.1")), String.valueOf(strategy.getDefaultVersion()));
    assertThrows(
        Exception.class,
        () -> strategy.validateVersion(strategy.parseVersion("9"), new MockHttpServletRequest()));
  }

  @Test
  void theReactiveFilterPublishesTheRequestContextAndRejectsMissingHeaders() {
    RequestMetadataResolver resolver =
        new RequestMetadataResolver(
            HeaderCatalog.of(List.of(HeaderDefinition.required("X-Channel"))),
            List.of("/actuator/**"),
            "X-Channel",
            Clock.systemUTC());
    RequestMetadataWebFilter filter = new RequestMetadataWebFilter(resolver, null);
    assertEquals(RequestMetadataFilter.ORDER, filter.getOrder());

    MockServerWebExchange missing =
        MockServerWebExchange.from(MockServerHttpRequest.get("/v1/accounts"));
    filter.filter(missing, ex -> Mono.error(new AssertionError("must not run"))).block();
    assertEquals(400, missing.getResponse().getStatusCode().value());
    assertThat(missing.getResponse().getBodyAsString().block())
        .contains("AXZ-CORE-0002")
        .contains("X-Channel");
    assertNotNull(missing.getResponse().getHeaders().getFirst("X-Correlation-Id"));

    MockServerWebExchange ok =
        MockServerWebExchange.from(
            MockServerHttpRequest.get("/v1/accounts")
                .header("X-Channel", "mobile")
                .header("X-Correlation-Id", "corr-12345678"));
    AtomicReference<RequestMetadata> inContext = new AtomicReference<>();
    filter
        .filter(
            ok,
            ex ->
                Mono.deferContextual(
                    ctx -> {
                      inContext.set(ctx.get(RequestContext.KEY));
                      return Mono.<Void>empty();
                    }))
        .block();
    assertEquals("mobile", inContext.get().channel());
    assertEquals("corr-12345678", ok.getResponse().getHeaders().getFirst("X-Correlation-Id"));
    RequestMetadata attribute = ok.getAttribute(RequestContext.KEY);
    assertEquals("corr-12345678", attribute.correlationId());

    MockServerWebExchange health =
        MockServerWebExchange.from(MockServerHttpRequest.get("/actuator/health"));
    AtomicReference<Boolean> reached = new AtomicReference<>(false);
    filter
        .filter(
            health,
            ex -> {
              reached.set(true);
              return Mono.empty();
            })
        .block();
    assertTrue(reached.get());
  }

  @Test
  void theContextAccessorRestoresTheRequestOnAnotherThread() {
    RequestContextAccessor accessor = new RequestContextAccessor();
    assertEquals(RequestContext.KEY, accessor.key());
    assertNull(accessor.getValue());
    RequestMetadata m = RequestMetadata.builder("corr-12345678", "req-12345678").build();
    accessor.setValue(m);
    try {
      assertEquals(m, accessor.getValue());
    } finally {
      accessor.setValue();
    }
    assertTrue(RequestContext.current().isEmpty());
  }

  @Test
  void safeServerDefaultsHaveTheLowestPrecedence() {
    MockEnvironment env =
        new MockEnvironment().withProperty("server.error.include-message", "always");
    AxizWebEnvironmentPostProcessor epp = new AxizWebEnvironmentPostProcessor();
    epp.postProcessEnvironment(env, new SpringApplication());
    assertEquals("never", env.getProperty("server.error.include-stacktrace"));
    assertEquals("graceful", env.getProperty("server.shutdown"));
    assertEquals("always", env.getProperty("server.error.include-message"));
    assertEquals(Ordered.LOWEST_PRECEDENCE, epp.getOrder());
  }

  record Body(String id, @Nullable String note) {}

  @Test
  void jsonOmitsNullsUnlessConfigured() {
    new WebApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(AxizWebAutoConfiguration.class))
        .run(
            ctx -> {
              JsonMapper.Builder builder = JsonMapper.builder();
              ctx.getBean(JsonMapperBuilderCustomizer.class).customize(builder);
              assertEquals(
                  "{\"id\":\"1\"}", builder.build().writeValueAsString(new Body("1", null)));
            });
    new WebApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(AxizWebAutoConfiguration.class))
        .withPropertyValues("axiz.web.json.include-nulls=true")
        .run(
            ctx -> {
              JsonMapper.Builder builder = JsonMapper.builder();
              ctx.getBean(JsonMapperBuilderCustomizer.class).customize(builder);
              assertEquals(
                  "{\"id\":\"1\",\"note\":null}",
                  builder.build().writeValueAsString(new Body("1", null)));
            });
  }

  @Test
  void requestBodiesWithUndeclaredPropertiesAreRejectedUnlessConfigured() {
    String massAssignment = "{\"id\":\"1\",\"note\":\"n\",\"status\":\"APPROVED\"}";
    new WebApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(AxizWebAutoConfiguration.class))
        .run(
            ctx -> {
              JsonMapper.Builder builder = JsonMapper.builder();
              ctx.getBean(JsonMapperBuilderCustomizer.class).customize(builder);
              JsonMapper mapper = builder.build();
              org.junit.jupiter.api.Assertions.assertThrows(
                  tools.jackson.databind.exc.UnrecognizedPropertyException.class,
                  () -> mapper.readValue(massAssignment, Body.class));
            });
    new WebApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(AxizWebAutoConfiguration.class))
        .withPropertyValues("axiz.web.json.reject-unknown-properties=false")
        .run(
            ctx -> {
              JsonMapper.Builder builder = JsonMapper.builder();
              ctx.getBean(JsonMapperBuilderCustomizer.class).customize(builder);
              assertEquals("1", builder.build().readValue(massAssignment, Body.class).id());
            });
  }

  @Test
  void reactiveServicesGetTheWebFilterAndTheConventionsCanBeSwitchedOff() {
    new ReactiveWebApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(AxizWebAutoConfiguration.class))
        .run(
            ctx ->
                assertThat(ctx)
                    .hasSingleBean(RequestMetadataWebFilter.class)
                    .hasSingleBean(AxizWebFluxConfigurer.class)
                    .doesNotHaveBean(RequestMetadataFilter.class));
    new WebApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(AxizWebAutoConfiguration.class))
        .withPropertyValues("axiz.web.enabled=false")
        .run(ctx -> assertThat(ctx).doesNotHaveBean(RequestMetadataResolver.class));
  }

  @Test
  void theReactiveConfigurerAppliesTheSameCorsAndVersionRules() {
    final class Cors extends org.springframework.web.reactive.config.CorsRegistry {
      Map<String, CorsConfiguration> configurations() {
        return getCorsConfigurations();
      }
    }
    final class Versions extends org.springframework.web.reactive.config.ApiVersionConfigurer {
      org.springframework.web.reactive.accept.ApiVersionStrategy strategy() {
        return getApiVersionStrategy();
      }
    }
    AxizWebFluxConfigurer configurer =
        new AxizWebFluxConfigurer(
            properties(
                "axiz.web.cors.enabled=true",
                "axiz.web.cors.allowed-origin-patterns=https://*.bank.example",
                "axiz.web.api-version.enabled=true",
                "axiz.web.api-version.default-version=1"),
            new PageRequestParser(20, 100));
    Cors cors = new Cors();
    configurer.addCorsMappings(cors);
    assertNotNull(cors.configurations().get("/**").checkOrigin("https://app.bank.example"));
    Versions versions = new Versions();
    configurer.configureApiVersioning(versions);
    MockServerWebExchange exchange =
        MockServerWebExchange.from(MockServerHttpRequest.get("/").header("API-Version", "1"));
    assertEquals("1", versions.strategy().resolveVersion(exchange));
    assertEquals(Duration.ofMinutes(30), properties().cors().maxAge());
  }
}
