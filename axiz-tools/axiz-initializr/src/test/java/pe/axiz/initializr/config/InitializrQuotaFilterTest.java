package pe.axiz.initializr.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import pe.axiz.core.security.InMemoryRateLimiter;
import pe.axiz.core.security.RateLimiter;

class InitializrQuotaFilterTest {
  private final InitializrLimits limits = new InitializrLimits(Duration.ofMinutes(1), 10, 1, 20, 1);
  private final InitializrQuotaFilter filter =
      new InitializrQuotaFilter(new InMemoryRateLimiter(Clock.systemUTC()), limits);
  private final FilterChain success =
      (request, response) -> response.getWriter().write("completed");

  @AfterEach
  void clearIdentity() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void quotasArePerTrustedSubjectAndOperationAndHeadersDoNotChangeIdentity() throws Exception {
    identity("alice");
    assertThat(call(filter, "/v1/projects/github", success).getStatus()).isEqualTo(200);
    MockHttpServletResponse denied = call(filter, "/v1/projects/github", success);
    assertThat(denied.getStatus()).isEqualTo(429);
    assertThat(denied.getHeader("Retry-After")).isNotBlank();
    assertThat(call(filter, "/v1/projects", success).getStatus()).isEqualTo(200);
    identity("bob");
    assertThat(call(filter, "/v1/projects/github", success).getStatus()).isEqualTo(200);
  }

  @Test
  void encodedSegmentsShareTheCanonicalQuotaAndMvcNonMatchesRemainNonMatches() throws Exception {
    identity("alice");
    assertThat(call(filter, "/v1/proj%65cts/github", success).getStatus()).isEqualTo(200);
    assertThat(call(filter, "/v1/projects/github", success).getStatus()).isEqualTo(429);
    assertThat(call(filter, "/v1/projects/%67ithub", success).getStatus()).isEqualTo(429);
    // La coincidencia PathPattern por defecto de MVC distingue mayúsculas y minúsculas y no admite
    // barra final opcional.
    assertThat(call(filter, "/v1/Projects/github", success).getStatus()).isEqualTo(200);
    assertThat(call(filter, "/v1/projects/github/", success).getStatus()).isEqualTo(200);
  }

  @Test
  void refusesUnauthenticatedWorkAndFailsClosedWhenRedisIsUnavailable() throws Exception {
    assertThat(call(filter, "/v1/projects", success).getStatus()).isEqualTo(401);
    identity("alice");
    RateLimiter unavailable =
        (key, maximum, window) -> {
          throw new IllegalStateException("sensitive transport error");
        };
    MockHttpServletResponse response =
        call(new InitializrQuotaFilter(unavailable, limits), "/v1/projects", success);
    assertThat(response.getStatus()).isEqualTo(503);
    assertThat(response.getContentAsString()).doesNotContain("sensitive", "completed");
  }

  @Test
  void concurrencyPermitRemainsHeldUntilWorkEndsAndIsReleasedAfterFailure() throws Exception {
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
      var work =
          executor.submit(
              () -> {
                identity("alice");
                try {
                  return call(
                      filter,
                      "/v1/projects",
                      (request, response) -> {
                        entered.countDown();
                        try {
                          if (!release.await(5, TimeUnit.SECONDS))
                            throw new ServletException("Test latch expired");
                        } catch (InterruptedException interrupted) {
                          Thread.currentThread().interrupt();
                          throw new ServletException(interrupted);
                        }
                      });
                } finally {
                  clearIdentity();
                }
              });
      try {
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
        identity("bob");
        assertThat(call(filter, "/v1/projects", success).getStatus()).isEqualTo(429);
      } finally {
        release.countDown();
      }
      assertThat(work.get(5, TimeUnit.SECONDS).getStatus()).isEqualTo(200);
    }
    identity("bob");
    assertThatThrownBy(
            () ->
                call(
                    filter,
                    "/v1/projects",
                    (request, response) -> {
                      throw new ServletException("failed work");
                    }))
        .isInstanceOf(ServletException.class);
    assertThat(call(filter, "/v1/projects", success).getContentAsString()).isEqualTo("completed");
  }

  private static MockHttpServletResponse call(
      InitializrQuotaFilter filter, String path, FilterChain chain) throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
    request.addHeader("X-User", "untrusted-" + java.util.UUID.randomUUID());
    MockHttpServletResponse response = new MockHttpServletResponse();
    filter.doFilter(request, response, chain);
    return response;
  }

  private static void identity(String name) {
    SecurityContextHolder.getContext()
        .setAuthentication(new UsernamePasswordAuthenticationToken(name, "unused", List.of()));
  }
}
