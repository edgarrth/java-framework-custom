package pe.axiz.spring.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.core.MethodParameter;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.reactive.BindingContext;
import org.springframework.web.server.ServerWebExchange;
import pe.axiz.core.api.HeaderCatalog;
import pe.axiz.core.api.HeaderDefinition;
import pe.axiz.core.api.PageRequest;
import pe.axiz.core.api.Sort;
import pe.axiz.core.context.RequestContext;
import pe.axiz.core.context.RequestMetadata;
import pe.axiz.core.errors.ValidationException;

class WebTest {

  private final RequestMetadataResolver resolver =
      new RequestMetadataResolver(
          HeaderCatalog.of(List.of(HeaderDefinition.required("X-Channel").withPattern("[a-z]+"))),
          List.of("/actuator/**"),
          "X-Channel",
          Clock.systemUTC());

  @Test
  void pagingIsValidatedAndSortRestricted() {
    PageRequestParser parser = new PageRequestParser(20, 100);
    PageRequest p = parser.parse("2", null, List.of("createdAt,desc"), null);
    assertEquals(2, p.page());
    assertEquals(20, p.size());
    assertEquals(Sort.Direction.DESC, p.sort().orders().get(0).direction());
    assertThrows(ValidationException.class, () -> parser.parse("x", "500", List.of(), null));
    // malformed sort expressions are the client's error: 400 with a violation on "sort", as the
    // audit reproduced over HTTP (sort=name;drop table, sort=$invalid answered 500 before)
    for (String sort :
        List.of(
            "name;drop table", "$invalid", "a b,desc", "name,wrong", "name,", "name,asc,desc")) {
      ValidationException e =
          assertThrows(
              ValidationException.class, () -> parser.parse("0", "10", List.of(sort), null));
      assertEquals("sort", e.violations().get(0).field());
      assertEquals(400, e.category().httpStatus());
    }
  }

  /** Controller method shape the resolvers serve. */
  @SuppressWarnings("unused")
  private void list(PageRequest page) {}

  @Test
  void bothWebStacksAnswerAMalformedSortWithAViolation() throws Exception {
    PageRequestParser parser = new PageRequestParser(20, 100);
    MethodParameter parameter =
        new MethodParameter(WebTest.class.getDeclaredMethod("list", PageRequest.class), 0);

    MockHttpServletRequest servlet = new MockHttpServletRequest("GET", "/v1/items");
    servlet.addParameter("sort", "$invalid");
    ValidationException mvc =
        assertThrows(
            ValidationException.class,
            () ->
                new PageRequestArgumentResolver(parser)
                    .resolveArgument(parameter, null, new ServletWebRequest(servlet), null));
    assertEquals("sort", mvc.violations().get(0).field());

    ServerWebExchange exchange =
        MockServerWebExchange.from(
            MockServerHttpRequest.get("/v1/items?sort={s}", "name;drop table").build());
    ValidationException webflux =
        assertThrows(
            ValidationException.class,
            () ->
                new ReactivePageRequestArgumentResolver(parser)
                    .resolveArgumentValue(parameter, new BindingContext(), exchange));
    assertEquals("sort", webflux.violations().get(0).field());
  }

  @Test
  void excludedPathsUsePathPatterns() {
    assertTrue(resolver.isExcluded("/actuator/health"));
    assertFalse(resolver.isExcluded("/payments/actuator/health"));
    assertEquals(List.of("X-Channel"), resolver.invalidHeaders("/payments", h -> null));
    assertEquals(List.of(), resolver.invalidHeaders("/actuator/health", h -> null));
  }

  @Test
  void filterRejectsMissingHeaderAndReusesSafeCorrelationId() throws Exception {
    RequestMetadataFilter filter = new RequestMetadataFilter(resolver, null);
    MockHttpServletRequest bad = new MockHttpServletRequest("GET", "/payments");
    bad.addHeader("X-Correlation-Id", "bad id\r\nforged");
    MockHttpServletResponse badResponse = new MockHttpServletResponse();
    filter.doFilter(bad, badResponse, new MockFilterChain());
    assertEquals(400, badResponse.getStatus());
    assertNotNull(badResponse.getHeader("X-Correlation-Id"));
    assertFalse(badResponse.getHeader("X-Correlation-Id").contains("forged"));
    assertTrue(badResponse.getContentAsString().contains("X-Channel"));

    AtomicReference<RequestMetadata> seen = new AtomicReference<>();
    MockHttpServletRequest ok = new MockHttpServletRequest("GET", "/payments");
    ok.addHeader("X-Channel", "mobile");
    ok.addHeader("X-Correlation-Id", "corr-12345678");
    MockHttpServletResponse okResponse = new MockHttpServletResponse();
    filter.doFilter(ok, okResponse, (req, res) -> seen.set(RequestContext.get()));
    assertEquals(200, okResponse.getStatus());
    assertEquals("corr-12345678", okResponse.getHeader("X-Correlation-Id"));
    assertEquals("mobile", seen.get().channel());
    assertEquals("mobile", seen.get().attributes().get("X-Channel"));
    assertTrue(RequestContext.current().isEmpty());
  }

  @Test
  void autoConfigurationRegistersServletBeans() {
    new WebApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(AxizWebAutoConfiguration.class))
        .withPropertyValues(
            "axiz.web.headers[0].name=X-Channel", "axiz.web.headers[0].required=true")
        .run(
            ctx -> {
              assertTrue(
                  ctx.getBean(HeaderCatalog.class).find("x-channel").orElseThrow().required());
              assertNotNull(ctx.getBean(RequestMetadataFilter.class));
              assertNotNull(ctx.getBean(AxizWebMvcConfigurer.class));
            });
  }

  @Test
  void anOptionalApiVersionHeaderAlwaysHasADefault() {
    // without a default, Spring WebFlux finds no handler for requests without the header (404)
    assertEquals(
        "1",
        new AxizWebProperties.ApiVersion(true, "API-Version", null, List.of("2", "1.1", "1"), false)
            .effectiveDefaultVersion());
    assertEquals(
        "2",
        new AxizWebProperties.ApiVersion(true, "API-Version", "2", List.of("1", "2"), false)
            .effectiveDefaultVersion());
    assertEquals(
        null,
        new AxizWebProperties.ApiVersion(true, "API-Version", null, List.of("1"), true)
            .effectiveDefaultVersion());
    assertThrows(
        IllegalArgumentException.class,
        () -> new AxizWebProperties.ApiVersion(true, "API-Version", null, List.of(), false));
  }
}
