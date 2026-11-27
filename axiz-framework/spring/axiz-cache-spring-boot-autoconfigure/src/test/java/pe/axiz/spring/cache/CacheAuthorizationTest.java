package pe.axiz.spring.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.Advisor;
import org.springframework.aop.framework.Advised;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.Ordered;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PostAuthorize;
import org.springframework.security.access.prepost.PostFilter;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authorization.method.AuthorizationInterceptorsOrder;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.method.configuration.EnableReactiveMethodSecurity;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import pe.axiz.core.cache.AxizCacheable;
import pe.axiz.core.cache.CacheScope;
import pe.axiz.core.tenancy.TenantContext;
import reactor.core.publisher.Mono;

/**
 * La caché nunca reemplaza la autorización de métodos: un invocante sin la autoridad se rechaza con
 * la caché fría y con la caché caliente, y un invocante con ella sigue leyendo de la caché.
 */
class CacheAuthorizationTest {

  private static final String SECRET = UUID.randomUUID().toString();
  private static final Duration T = Duration.ofSeconds(5);

  /** Métodos bloqueantes protegidos antes y después de la llamada. */
  public static class Accounts {
    private final AtomicInteger calls = new AtomicInteger();

    // se lee a través del proxy: un campo de un proxy basado en clases no es el del objetivo
    public int calls() {
      return calls.get();
    }

    @PreAuthorize("hasAuthority('READ_PRIVATE')")
    @AxizCacheable("private-pre")
    public String before(String account) {
      calls.incrementAndGet();
      return SECRET;
    }

    @PostAuthorize("hasAuthority('READ_PRIVATE')")
    @AxizCacheable(value = "private-post", scope = CacheScope.CALLER)
    public String after(String account) {
      calls.incrementAndGet();
      return SECRET;
    }

    @PostFilter("hasAuthority('READ_PRIVATE') or filterObject != 'private'")
    @AxizCacheable("filtered")
    public List<String> lines(String account) {
      calls.incrementAndGet();
      return new ArrayList<>(List.of("public", "private"));
    }
  }

  /** El mismo contrato sobre Reactor. */
  public static class ReactiveAccounts {
    private final AtomicInteger calls = new AtomicInteger();

    public int calls() {
      return calls.get();
    }

    @PreAuthorize("hasAuthority('READ_PRIVATE')")
    @AxizCacheable("private-pre")
    public Mono<String> before(String account) {
      return Mono.fromSupplier(
          () -> {
            calls.incrementAndGet();
            return SECRET;
          });
    }

    @PostAuthorize("hasAuthority('READ_PRIVATE')")
    @AxizCacheable(value = "private-post", scope = CacheScope.CALLER)
    public Mono<String> after(String account) {
      return Mono.fromSupplier(
          () -> {
            calls.incrementAndGet();
            return SECRET;
          });
    }
  }

  @Configuration(proxyBeanMethods = false)
  @EnableMethodSecurity(proxyTargetClass = true, jsr250Enabled = true, securedEnabled = true)
  @Import(AxizCacheAutoConfiguration.class)
  static class ServletConfig {
    @Bean
    Accounts accounts() {
      return new Accounts();
    }
  }

  @Configuration(proxyBeanMethods = false)
  @EnableReactiveMethodSecurity(proxyTargetClass = true)
  @Import(AxizCacheAutoConfiguration.class)
  static class ReactiveConfig {
    @Bean
    ReactiveAccounts accounts() {
      return new ReactiveAccounts();
    }
  }

  @AfterEach
  void clear() {
    SecurityContextHolder.clearContext();
    TenantContext.restore(null);
  }

  private static JwtAuthenticationToken user(boolean allowed) {
    Jwt jwt =
        Jwt.withTokenValue("synthetic")
            .header("alg", "RS256")
            .issuer("https://issuer.example")
            .subject("same-user")
            .claim("azp", "app")
            .build();
    return new JwtAuthenticationToken(
        jwt, AuthorityUtils.createAuthorityList(allowed ? "READ_PRIVATE" : "OTHER"));
  }

  private static <T> T as(boolean allowed, Supplier<T> call) {
    SecurityContextHolder.getContext().setAuthentication(user(allowed));
    return call.get();
  }

  private static String reactive(boolean allowed, Mono<String> call) {
    return call.contextWrite(ReactiveSecurityContextHolder.withAuthentication(user(allowed)))
        .contextWrite(c -> c.put(CacheScopes.TENANT_KEY, "bank"))
        .block(T);
  }

  @Test
  void theCacheRunsInsideEveryAuthorizationInterceptor() {
    assertTrue(
        AxizCacheableInterceptor.ORDER > AuthorizationInterceptorsOrder.POST_FILTER.getOrder());
    assertTrue(AxizCacheableInterceptor.ORDER < Ordered.LOWEST_PRECEDENCE);
    try (var ctx = new AnnotationConfigApplicationContext(ServletConfig.class)) {
      Advisor[] advisors = ((Advised) ctx.getBean("accounts")).getAdvisors();
      int cache = -1;
      int lastSecurity = -1;
      for (int i = 0; i < advisors.length; i++) {
        String name = advisors[i].getAdvice().getClass().getName();
        if (name.endsWith("AxizCacheableInterceptor")) {
          cache = i;
        } else if (name.startsWith("org.springframework.security")) {
          lastSecurity = i;
        }
      }
      assertTrue(cache > lastSecurity && lastSecurity >= 0, Arrays.toString(advisors));
    }
  }

  @Test
  void preAuthorizeIsCheckedOnEveryHit() {
    TenantContext.restore("bank");
    try (var ctx = new AnnotationConfigApplicationContext(ServletConfig.class)) {
      Accounts accounts = ctx.getBean(Accounts.class);
      assertThrows(AccessDeniedException.class, () -> as(false, () -> accounts.before("1")));
      assertEquals(SECRET, as(true, () -> accounts.before("1")));
      assertThrows(AccessDeniedException.class, () -> as(false, () -> accounts.before("1")));
      assertEquals(SECRET, as(true, () -> accounts.before("1")));
      assertEquals(1, accounts.calls(), "an authorized caller still reads from the cache");
    }
  }

  @Test
  void postAuthorizeAndPostFilterApplyToCachedValues() {
    TenantContext.restore("bank");
    try (var ctx = new AnnotationConfigApplicationContext(ServletConfig.class)) {
      Accounts accounts = ctx.getBean(Accounts.class);
      assertThrows(AccessDeniedException.class, () -> as(false, () -> accounts.after("1")));
      assertEquals(SECRET, as(true, () -> accounts.after("1")));
      // el mismo subject pierde la autoridad: no se devuelve el valor cacheado
      assertThrows(AccessDeniedException.class, () -> as(false, () -> accounts.after("1")));

      assertEquals(List.of("public", "private"), as(true, () -> accounts.lines("1")));
      assertEquals(List.of("public"), as(false, () -> accounts.lines("1")));
    }
  }

  @Test
  void reactiveMethodSecurityIsCheckedOnEveryHit() {
    try (var ctx = new AnnotationConfigApplicationContext(ReactiveConfig.class)) {
      ReactiveAccounts accounts = ctx.getBean(ReactiveAccounts.class);
      for (Function<ReactiveAccounts, Mono<String>> call :
          List.<Function<ReactiveAccounts, Mono<String>>>of(
              a -> a.before("1"), a -> a.after("1"))) {
        assertThrows(AccessDeniedException.class, () -> reactive(false, call.apply(accounts)));
        assertEquals(SECRET, reactive(true, call.apply(accounts)));
        assertThrows(AccessDeniedException.class, () -> reactive(false, call.apply(accounts)));
        assertEquals(SECRET, reactive(true, call.apply(accounts)));
      }
      // una carga por método: la llamada @PostAuthorize denegada carga el valor y las llamadas
      // posteriores aciertan
      assertEquals(2, accounts.calls());
    }
  }
}
