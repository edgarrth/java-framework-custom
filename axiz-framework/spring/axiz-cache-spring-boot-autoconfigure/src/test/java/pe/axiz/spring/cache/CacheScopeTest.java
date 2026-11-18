package pe.axiz.spring.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.Advisor;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import pe.axiz.core.cache.AxizCacheable;
import pe.axiz.core.cache.CacheScope;
import pe.axiz.core.security.AxizPrincipal;
import pe.axiz.core.tenancy.TenantContext;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Who shares an entry: the tenant by default, the caller on request, everyone only on purpose. */
class CacheScopeTest {

  private static final Duration T = Duration.ofSeconds(5);
  private static final String ISSUER_A = "https://issuer-a.example";
  private static final String ISSUER_B = "https://issuer-b.example";

  /** Reads the tenant from the context and takes a tenant-local id, like a real repository. */
  public static class Profiles {
    final AtomicInteger calls = new AtomicInteger();

    @AxizCacheable("profiles")
    public String profile(String customerId) {
      calls.incrementAndGet();
      return TenantContext.current().orElse("none") + ":" + customerId;
    }

    @AxizCacheable(value = "profiles", scope = CacheScope.GLOBAL)
    public String bank(String code) {
      calls.incrementAndGet();
      return TenantContext.current().orElse("none") + ":" + code;
    }

    @AxizCacheable(value = "profiles", scope = CacheScope.CALLER)
    public String mine(String view) {
      calls.incrementAndGet();
      return SecurityContextHolder.getContext().getAuthentication() == null
          ? "anonymous:" + view
          : SecurityContextHolder.getContext().getAuthentication().getName() + ":" + view;
    }

    @AxizCacheable("profiles")
    public Mono<String> reactiveProfile(String customerId) {
      return Mono.deferContextual(
          ctx -> {
            calls.incrementAndGet();
            return Mono.just(ctx.getOrDefault(CacheScopes.TENANT_KEY, "none") + ":" + customerId);
          });
    }

    @AxizCacheable("profiles")
    public Flux<String> reactiveLines(String customerId) {
      return Flux.deferContextual(
          ctx -> {
            calls.incrementAndGet();
            return Flux.just(ctx.getOrDefault(CacheScopes.TENANT_KEY, "none") + ":" + customerId);
          });
    }

    @AxizCacheable(value = "profiles", scope = CacheScope.CALLER)
    public Mono<String> reactiveMine(String view) {
      return ReactiveSecurityContextHolder.getContext()
          .map(c -> c.getAuthentication().getName() + ":" + view)
          .defaultIfEmpty("anonymous:" + view)
          .doOnSubscribe(s -> calls.incrementAndGet());
    }
  }

  @AfterEach
  void clear() {
    TenantContext.restore(null);
    SecurityContextHolder.clearContext();
  }

  private static void withProfiles(Consumer<Profiles[]> test) {
    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(AxizCacheAutoConfiguration.class))
        .withPropertyValues("spring.application.name=svc")
        .run(
            ctx -> {
              Profiles target = new Profiles();
              ProxyFactory pf = new ProxyFactory(target);
              pf.setProxyTargetClass(true);
              pf.addAdvisor(ctx.getBean("axizCacheableAdvisor", Advisor.class));
              test.accept(new Profiles[] {target, (Profiles) pf.getProxy()});
            });
  }

  @Test
  void tenantsNeverReadEachOthersEntries() {
    withProfiles(
        p -> {
          Profiles target = p[0];
          Profiles profiles = p[1];
          TenantContext.restore("tenant-a");
          assertEquals("tenant-a:C-1001", profiles.profile("C-1001"));
          assertEquals("tenant-a:C-1001", profiles.profile("C-1001"), "same tenant: a hit");
          TenantContext.restore("tenant-b");
          assertEquals("tenant-b:C-1001", profiles.profile("C-1001"));
          assertEquals(2, target.calls.get());
          TenantContext.restore(null);
          assertEquals("none:C-1001", profiles.profile("C-1001"), "no tenant is its own scope");
          assertEquals(3, target.calls.get());
        });
  }

  @Test
  void globalEntriesAreSharedOnPurpose() {
    withProfiles(
        p -> {
          TenantContext.restore("tenant-a");
          assertEquals("tenant-a:BCP", p[1].bank("BCP"));
          TenantContext.restore("tenant-b");
          assertEquals("tenant-a:BCP", p[1].bank("BCP"), "a global cache ignores the tenant");
          assertEquals(1, p[0].calls.get());
        });
  }

  @Test
  void callerEntriesNeedAnAuthenticatedCaller() {
    withProfiles(
        p -> {
          Profiles target = p[0];
          Profiles profiles = p[1];
          SecurityContextHolder.getContext().setAuthentication(user("ana"));
          assertEquals("ana:summary", profiles.mine("summary"));
          assertEquals("ana:summary", profiles.mine("summary"));
          assertEquals(1, target.calls.get());
          SecurityContextHolder.getContext().setAuthentication(user("luis"));
          assertEquals("luis:summary", profiles.mine("summary"), "another caller: another entry");
          assertEquals(2, target.calls.get());

          SecurityContextHolder.clearContext();
          profiles.mine("summary");
          profiles.mine("summary");
          assertEquals(4, target.calls.get(), "without a caller nothing is cached");
          SecurityContextHolder.getContext()
              .setAuthentication(
                  new AnonymousAuthenticationToken(
                      "key",
                      "anonymousUser",
                      AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS")));
          profiles.mine("summary");
          profiles.mine("summary");
          assertEquals(6, target.calls.get(), "an anonymous token is not a caller");
        });
  }

  @Test
  void reactiveCallsReadTheTenantWhenSubscribed() {
    withProfiles(
        p -> {
          Profiles target = p[0];
          Profiles profiles = p[1];
          Mono<String> assembled = profiles.reactiveProfile("C-1001");
          assertEquals(
              "tenant-a:C-1001",
              assembled.contextWrite(c -> c.put(CacheScopes.TENANT_KEY, "tenant-a")).block(T));
          assertEquals(
              "tenant-a:C-1001",
              profiles
                  .reactiveProfile("C-1001")
                  .contextWrite(c -> c.put(CacheScopes.TENANT_KEY, "tenant-a"))
                  .block(T));
          assertEquals(1, target.calls.get());
          assertEquals(
              "tenant-b:C-1001",
              assembled.contextWrite(c -> c.put(CacheScopes.TENANT_KEY, "tenant-b")).block(T),
              "the same publisher subscribed by another tenant");
          assertEquals(2, target.calls.get());

          assertEquals(
              List.of("tenant-a:C-1"),
              profiles
                  .reactiveLines("C-1")
                  .contextWrite(c -> c.put(CacheScopes.TENANT_KEY, "tenant-a"))
                  .collectList()
                  .block(T));
          assertEquals(
              List.of("tenant-b:C-1"),
              profiles
                  .reactiveLines("C-1")
                  .contextWrite(c -> c.put(CacheScopes.TENANT_KEY, "tenant-b"))
                  .collectList()
                  .block(T));
          assertEquals(4, target.calls.get());
        });
  }

  @Test
  void reactiveCallerEntriesFollowTheSecurityContext() {
    withProfiles(
        p -> {
          Profiles target = p[0];
          Profiles profiles = p[1];
          assertEquals(
              "ana:summary",
              profiles
                  .reactiveMine("summary")
                  .contextWrite(ReactiveSecurityContextHolder.withAuthentication(user("ana")))
                  .block(T));
          assertEquals(
              "ana:summary",
              profiles
                  .reactiveMine("summary")
                  .contextWrite(ReactiveSecurityContextHolder.withAuthentication(user("ana")))
                  .block(T));
          assertEquals(1, target.calls.get());
          assertEquals(
              "luis:summary",
              profiles
                  .reactiveMine("summary")
                  .contextWrite(ReactiveSecurityContextHolder.withAuthentication(user("luis")))
                  .block(T));
          assertEquals("anonymous:summary", profiles.reactiveMine("summary").block(T));
          assertEquals("anonymous:summary", profiles.reactiveMine("summary").block(T));
          assertEquals(4, target.calls.get(), "without a caller nothing is cached");
        });
  }

  @Test
  void theScopeIsAlwaysThreeEncodedParts() {
    assertEquals("s=TENANT:s=tenant-a:-", CacheScopes.part(CacheScope.TENANT, "tenant-a", null));
    assertEquals("s=GLOBAL:-:-", CacheScopes.part(CacheScope.GLOBAL, null, null));
    assertNotEquals(
        CacheScopes.part(CacheScope.CALLER, "a", "b:c"),
        CacheScopes.part(CacheScope.CALLER, "a:b", "c"),
        "separators inside values are escaped");
  }

  @Test
  void theCallerIsTheIssuerSubjectAndClientOfTheToken() {
    withProfiles(
        p -> {
          Profiles target = p[0];
          Profiles profiles = p[1];
          SecurityContextHolder.getContext().setAuthentication(token(ISSUER_A, "ana", "app"));
          assertEquals("ana:summary", profiles.mine("summary"));
          assertEquals("ana:summary", profiles.mine("summary"), "same issuer, subject and client");
          assertEquals(1, target.calls.get());

          // another trust domain reuses the subject: never the same entry
          SecurityContextHolder.getContext().setAuthentication(token(ISSUER_B, "ana", "app"));
          profiles.mine("summary");
          assertEquals(2, target.calls.get());
          // the same user through another application
          SecurityContextHolder.getContext().setAuthentication(token(ISSUER_A, "ana", "web"));
          profiles.mine("summary");
          assertEquals(3, target.calls.get());
          // a machine token (no subject) of that application
          SecurityContextHolder.getContext().setAuthentication(token(ISSUER_A, null, "app"));
          profiles.mine("summary");
          profiles.mine("summary");
          assertEquals(4, target.calls.get());
        });
  }

  @Test
  void axizPrincipalsAreTokenCallersToo() {
    withProfiles(
        p -> {
          Profiles target = p[0];
          Profiles profiles = p[1];
          SecurityContextHolder.getContext().setAuthentication(axiz(ISSUER_A, "ana"));
          profiles.mine("summary");
          profiles.mine("summary");
          assertEquals(1, target.calls.get());
          SecurityContextHolder.getContext().setAuthentication(axiz(ISSUER_B, "ana"));
          profiles.mine("summary");
          assertEquals(2, target.calls.get());
          assertNotEquals(
              new DefaultCacheCallerResolver().identity(axiz(ISSUER_A, "ana")),
              new DefaultCacheCallerResolver().identity(axiz(ISSUER_B, "ana")));
        });
  }

  @Test
  void anAuthenticationWithoutASafeIdentityIsNotCached() {
    withProfiles(
        p -> {
          TestingAuthenticationToken named = new TestingAuthenticationToken("ana", "n/a", "R");
          named.setAuthenticated(true);
          SecurityContextHolder.getContext().setAuthentication(named);
          p[1].mine("summary");
          p[1].mine("summary");
          assertEquals(2, p[0].calls.get(), "a bare name may repeat across trust domains");
          SecurityContextHolder.getContext()
              .setAuthentication(
                  new JwtAuthenticationToken(
                      Jwt.withTokenValue("t").header("alg", "none").subject("ana").build()));
          p[1].mine("summary");
          p[1].mine("summary");
          assertEquals(4, p[0].calls.get(), "a token without issuer is not cached");
        });
  }

  @Test
  void aServiceDeclaresItsOwnCallerIdentity() {
    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(AxizCacheAutoConfiguration.class))
        .withPropertyValues("spring.application.name=svc")
        .withBean(CacheCallerResolver.class, () -> a -> "local:" + a.getName())
        .run(
            ctx -> {
              Profiles target = new Profiles();
              ProxyFactory pf = new ProxyFactory(target);
              pf.setProxyTargetClass(true);
              pf.addAdvisor(ctx.getBean("axizCacheableAdvisor", Advisor.class));
              Profiles profiles = (Profiles) pf.getProxy();
              TestingAuthenticationToken named = new TestingAuthenticationToken("ana", "n/a", "R");
              named.setAuthenticated(true);
              SecurityContextHolder.getContext().setAuthentication(named);
              profiles.mine("summary");
              profiles.mine("summary");
              assertEquals(1, target.calls.get());
            });
  }

  @Test
  void reactiveCallersAreToldApartByIssuer() {
    withProfiles(
        p -> {
          for (String issuer : List.of(ISSUER_A, ISSUER_B, ISSUER_A)) {
            assertEquals(
                "ana:summary",
                p[1].reactiveMine("summary")
                    .contextWrite(
                        ReactiveSecurityContextHolder.withAuthentication(
                            token(issuer, "ana", "app")))
                    .block(T));
          }
          assertEquals(2, p[0].calls.get());
        });
  }

  private static JwtAuthenticationToken user(String name) {
    return token(ISSUER_A, name, "app");
  }

  private static JwtAuthenticationToken token(
      String issuer, @Nullable String subject, String client) {
    Jwt.Builder jwt =
        Jwt.withTokenValue("synthetic").header("alg", "RS256").issuer(issuer).claim("azp", client);
    if (subject != null) {
      jwt.subject(subject);
    }
    return new JwtAuthenticationToken(
        jwt.build(),
        AuthorityUtils.createAuthorityList("ROLE_USER"),
        subject != null ? subject : client);
  }

  private static TestingAuthenticationToken axiz(String issuer, String subject) {
    AxizPrincipal principal =
        AxizPrincipal.builder()
            .subject(subject)
            .clientId("app")
            .issuer(issuer)
            .audience(List.of())
            .scopes(Set.of())
            .roles(Set.of())
            .tenantId(null)
            .attributes(Map.of())
            .build();
    TestingAuthenticationToken token = new TestingAuthenticationToken(principal, "n/a", "R");
    token.setAuthenticated(true);
    return token;
  }
}
