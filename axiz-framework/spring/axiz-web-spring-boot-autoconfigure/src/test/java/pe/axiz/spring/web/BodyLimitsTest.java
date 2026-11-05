package pe.axiz.spring.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequestWrapper;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import tools.jackson.databind.json.JsonMapper;

class BodyLimitsTest {
  private final WebApplicationContextRunner servlet =
      new WebApplicationContextRunner()
          .withConfiguration(
              AutoConfigurations.of(
                  AxizWebAutoConfiguration.class, JacksonAutoConfiguration.class));

  @Test
  void servletRejectsDeclaredAndChunkedBodiesAndHonorsRouteLimits() {
    servlet
        .withPropertyValues(
            "axiz.web.max-request-body=8B",
            "axiz.web.body-limits[0].path=/upload",
            "axiz.web.body-limits[0].max-size=16B")
        .run(
            ctx -> {
              List<Filter> filters = ctx.getBeanProvider(Filter.class).orderedStream().toList();
              for (boolean chunked : List.of(false, true)) {
                var request = new MockHttpServletRequest("POST", "/payments");
                request.setContent(new byte[9]);
                var response = new MockHttpServletResponse();
                var called = new AtomicInteger();
                var wire =
                    chunked
                        ? new HttpServletRequestWrapper(request) {
                          @Override
                          public int getContentLength() {
                            return -1;
                          }

                          @Override
                          public long getContentLengthLong() {
                            return -1;
                          }
                        }
                        : request;
                FilterChain app =
                    (req, res) -> {
                      req.getInputStream().readAllBytes();
                      called.incrementAndGet();
                    };
                for (int i = filters.size() - 1; i >= 0; i--) {
                  Filter f = filters.get(i);
                  FilterChain next = app;
                  app = (req, res) -> f.doFilter(req, res, next);
                }
                app.doFilter(wire, response);
                assertEquals(413, response.getStatus());
                assertEquals(0, called.get());
                assertTrue(response.getContentType().startsWith("application/problem+json"));
              }
              var upload = new MockHttpServletRequest("POST", "/upload");
              upload.setContent(new byte[12]);
              var response = new MockHttpServletResponse();
              var called = new AtomicInteger();
              FilterChain app =
                  (req, res) -> {
                    req.getInputStream().readAllBytes();
                    called.incrementAndGet();
                  };
              for (int i = filters.size() - 1; i >= 0; i--) {
                Filter f = filters.get(i);
                FilterChain next = app;
                app = (req, res) -> f.doFilter(req, res, next);
              }
              app.doFilter(upload, response);
              assertEquals(1, called.get());
            });
  }

  @Test
  void reactiveRejectsDeclaredAndChunkedBodiesBeforeTheHandlerCompletes() {
    new ReactiveWebApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(AxizWebAutoConfiguration.class))
        .withPropertyValues("axiz.web.max-request-body=8B")
        .run(
            ctx -> {
              for (boolean declared : List.of(false, true)) {
                var builder = MockServerHttpRequest.post("/payments");
                if (declared) builder.header("Content-Length", "9");
                var exchange = MockServerWebExchange.from(builder.body("123456789"));
                var called = new AtomicInteger();
                WebFilterChain app =
                    e -> e.getRequest().getBody().then(Mono.fromRunnable(called::incrementAndGet));
                List<WebFilter> filters =
                    ctx.getBeanProvider(WebFilter.class).orderedStream().toList();
                for (int i = filters.size() - 1; i >= 0; i--) {
                  WebFilter f = filters.get(i);
                  WebFilterChain next = app;
                  app = e -> f.filter(e, next);
                }
                app.filter(exchange).block();
                assertEquals(413, exchange.getResponse().getStatusCode().value());
                assertEquals(0, called.get());
              }
            });
  }

  @Test
  void effectiveBootMapperEnforcesNumericAndDepthLimits() {
    servlet.run(
        ctx -> {
          JsonMapper mapper = ctx.getBean(JsonMapper.class);
          assertThrows(
              tools.jackson.core.JacksonException.class,
              () -> mapper.readValue("{\"n\":" + "9".repeat(1000) + "}", Object.class));
          assertThrows(
              tools.jackson.core.JacksonException.class,
              () -> mapper.readValue("[".repeat(65) + "0" + "]".repeat(65), Object.class));
        });
  }

  @Test
  void servletReadersAndSingleByteReadsEnforceTheSameChunkedLimitEvenWhenBindingHandlesTheError() {
    servlet
        .withPropertyValues("axiz.web.max-request-body=8B")
        .run(
            ctx -> {
              var filter = ctx.getBean(RequestBodyLimitFilter.class);
              for (boolean reader : List.of(false, true))
                for (boolean encoding : List.of(false, true))
                  for (int size : List.of(8, 9)) {
                    var request = new MockHttpServletRequest("POST", "/payments");
                    request.setContent(
                        "x".repeat(size).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    if (encoding) request.setCharacterEncoding("UTF-8");
                    var chunked =
                        new HttpServletRequestWrapper(request) {
                          @Override
                          public long getContentLengthLong() {
                            return -1;
                          }
                        };
                    var response = new MockHttpServletResponse();
                    filter.doFilter(
                        chunked,
                        response,
                        (req, res) -> {
                          var counted = (jakarta.servlet.http.HttpServletRequest) req;
                          org.junit.jupiter.api.Assertions.assertSame(
                              counted.getInputStream(), counted.getInputStream());
                          try {
                            if (reader) {
                              var input = counted.getReader();
                              while (input.read() != -1) {}
                            } else {
                              var input = counted.getInputStream();
                              while (input.read() != -1) {}
                            }
                          } catch (java.io.IOException tooLarge) {
                            ((jakarta.servlet.http.HttpServletResponse) res).setStatus(400);
                          }
                        });
                    assertEquals(size == 8 ? 200 : 413, response.getStatus());
                  }
            });
  }

  @Test
  void anUnrelatedTransportFailureIsPreserved() {
    servlet
        .withPropertyValues("axiz.web.max-request-body=8B")
        .run(
            ctx -> {
              var failure = new java.io.IOException("transport failed");
              var observed =
                  assertThrows(
                      java.io.IOException.class,
                      () ->
                          ctx.getBean(RequestBodyLimitFilter.class)
                              .doFilter(
                                  new MockHttpServletRequest("POST", "/payments"),
                                  new MockHttpServletResponse(),
                                  (req, res) -> {
                                    throw failure;
                                  }));
              org.junit.jupiter.api.Assertions.assertSame(failure, observed);
            });
  }
}
