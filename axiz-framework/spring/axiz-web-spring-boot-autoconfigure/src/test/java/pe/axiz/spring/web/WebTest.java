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
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import pe.axiz.core.commons.HeaderCatalog;
import pe.axiz.core.commons.HeaderDefinition;
import pe.axiz.core.commons.PageRequest;
import pe.axiz.core.commons.RequestContext;
import pe.axiz.core.commons.RequestMetadata;
import pe.axiz.core.commons.Sort;
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
    assertThrows(
        ValidationException.class, () -> parser.parse("0", "10", List.of("name;drop table"), null));
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
