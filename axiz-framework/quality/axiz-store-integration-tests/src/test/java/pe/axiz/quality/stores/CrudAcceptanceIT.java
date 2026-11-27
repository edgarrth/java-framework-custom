package pe.axiz.quality.stores;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.hibernate.SessionFactory;
import org.hibernate.cfg.Configuration;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.servlet.context.AnnotationConfigServletWebServerApplicationContext;
import org.springframework.boot.webmvc.autoconfigure.DispatcherServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.support.SimpleJpaRepository;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.DispatcherServlet;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.testcontainers.postgresql.PostgreSQLContainer;
import pe.axiz.core.errors.AccessException;
import pe.axiz.core.json.JsonReader;
import pe.axiz.quality.containers.TestContainerImages;
import pe.axiz.spring.errors.AxizErrorsAutoConfiguration;
import pe.axiz.spring.security.AxizSecurityAutoConfiguration;
import pe.axiz.spring.web.AxizWebAutoConfiguration;
import pe.axiz.structure.layered.CrudAction;
import pe.axiz.structure.layered.spring.CrudController;
import pe.axiz.structure.layered.spring.JpaCrudService;

/** Regresión con HTTP real, JWT firmado y PostgreSQL de los casos de asignación masiva e IDOR. */
class CrudAcceptanceIT {
  private static final PostgreSQLContainer POSTGRES = TestContainerImages.postgres();
  private static AnnotationConfigServletWebServerApplicationContext context;
  private static HttpServer issuer;
  private static RSAKey signingKey;
  private static HttpClient http;
  private static String endpoint;

  @Entity(name = "AuthorizedMaintenance")
  @Table(name = "authorized_maintenance")
  public static class Entry {
    @Id String id;
    @Version Long version;
    String kind;
    String owner;
    String name;
    BigDecimal dailyAmount;
    BigDecimal balance;
    String status;

    protected Entry() {}
  }

  public record Change(
      @NotBlank @Size(max = 80) @jakarta.validation.constraints.Pattern(regexp = "[^\\p{Cntrl}]*") String name,
      @Nullable Long version) {}

  public record View(
      String id,
      String owner,
      String name,
      Long version,
      BigDecimal dailyAmount,
      BigDecimal balance,
      String status) {}

  public static class Maintenance extends JpaCrudService<Entry, Change, View, String> {
    private final String kind;

    Maintenance(EntityManager em, String kind) {
      this(em, new SimpleJpaRepository<>(Entry.class, em), kind);
    }

    private Maintenance(
        EntityManager em, SimpleJpaRepository<Entry, String> repository, String kind) {
      super(Entry.class, repository, repository, em, kind);
      this.kind = kind;
    }

    private String caller() {
      return SecurityContextHolder.getContext().getAuthentication().getName();
    }

    protected Entry newEntity(Change request) {
      var entry = new Entry();
      entry.id = UUID.randomUUID().toString();
      entry.owner = caller();
      entry.kind = kind;
      entry.name = request.name();
      entry.dailyAmount = new BigDecimal("100.00");
      entry.balance = BigDecimal.ZERO;
      entry.status = "DRAFT";
      return entry;
    }

    protected void apply(Entry current, Change request) {
      current.name = request.name();
    }

    protected View toResponse(Entry e) {
      return new View(e.id, e.owner, e.name, e.version, e.dailyAmount, e.balance, e.status);
    }

    protected @Nullable Object expectedVersion(Change request) {
      return request.version();
    }

    protected void authorize(CrudAction action, @Nullable Entry entry) {
      if (entry != null && (!entry.owner.equals(caller()) || !entry.kind.equals(kind)))
        throw AccessException.forbidden("Resource is outside caller scope");
    }

    protected Specification<Entry> listScope() {
      String owner = caller();
      return (root, query, builder) ->
          builder.and(
              builder.equal(root.get("owner"), owner), builder.equal(root.get("kind"), kind));
    }

    protected Set<String> sortableFields() {
      return Set.of("name");
    }
  }

  @RestController
  @RequestMapping("/v1/accounts")
  public static class Accounts extends CrudController<Change, View, String> {
    Accounts(Maintenance service) {
      super(service, View::id);
    }
  }

  @RestController
  @RequestMapping("/v1/limits")
  public static class Limits extends CrudController<Change, View, String> {
    Limits(Maintenance service) {
      super(service, View::id);
    }
  }

  @org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
  @EnableWebMvc
  @EnableTransactionManagement(proxyTargetClass = true)
  @ImportAutoConfiguration({
    AxizErrorsAutoConfiguration.class,
    AxizWebAutoConfiguration.class,
    AxizSecurityAutoConfiguration.class
  })
  static class Application {
    @Bean
    TomcatServletWebServerFactory server() {
      var server = new TomcatServletWebServerFactory(0);
      try {
        server.setAddress(java.net.InetAddress.getByName("127.0.0.1"));
      } catch (java.net.UnknownHostException impossible) {
        throw new IllegalStateException(impossible);
      }
      return server;
    }

    @Bean
    DispatcherServlet dispatcherServlet() {
      return new DispatcherServlet();
    }

    @Bean
    DispatcherServletRegistrationBean servlet(DispatcherServlet servlet) {
      return new DispatcherServletRegistrationBean(servlet, "/");
    }

    @Bean(destroyMethod = "close")
    SessionFactory entityManagerFactory() {
      return new Configuration()
          .addAnnotatedClass(Entry.class)
          .setProperty("hibernate.connection.url", POSTGRES.getJdbcUrl())
          .setProperty("hibernate.connection.username", POSTGRES.getUsername())
          .setProperty("hibernate.connection.password", POSTGRES.getPassword())
          .setProperty("hibernate.connection.pool_size", "4")
          .setProperty("hibernate.hbm2ddl.auto", "create-drop")
          .buildSessionFactory();
    }

    @Bean
    JpaTransactionManager transactionManager(SessionFactory factory) {
      return new JpaTransactionManager(factory);
    }

    @Bean
    EntityManager entityManager(SessionFactory factory) {
      return SharedEntityManagerCreator.createSharedEntityManager(factory);
    }

    @Bean
    Maintenance accountsService(EntityManager em) {
      return new Maintenance(em, "account");
    }

    @Bean
    Maintenance limitsService(EntityManager em) {
      return new Maintenance(em, "limit");
    }

    @Bean
    Accounts accounts(Maintenance accountsService) {
      return new Accounts(accountsService);
    }

    @Bean
    Limits limits(Maintenance limitsService) {
      return new Limits(limitsService);
    }
  }

  @BeforeAll
  static void start() throws Exception {
    Docker.require();
    POSTGRES.start();
    signingKey = new RSAKeyGenerator(2048).keyID("crud-acceptance").generate();
    issuer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    issuer.createContext(
        "/keys",
        exchange -> {
          byte[] json =
              new JWKSet(signingKey.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().set("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, json.length);
          try (var body = exchange.getResponseBody()) {
            body.write(json);
          }
        });
    issuer.start();
    context = new AnnotationConfigServletWebServerApplicationContext();
    TestPropertyValues.of(
            "spring.application.name=crud-acceptance",
            "axiz.config.environment=test",
            "axiz.security.issuers[0].issuer=" + issuerUrl(),
            "axiz.security.issuers[0].jwk-set-uri=" + issuerUrl() + "/keys",
            "axiz.security.issuers[0].audiences[0]=crud",
            "axiz.security.authorization.rules[0].paths[0]=/v1/**",
            "axiz.security.authorization.rules[0].authorities[0]=SCOPE_maintenance:write")
        .applyTo(context);
    context.register(Application.class);
    context.refresh();
    endpoint = "http://127.0.0.1:" + context.getWebServer().getPort();
    http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
  }

  private static String issuerUrl() {
    return "http://127.0.0.1:" + issuer.getAddress().getPort();
  }

  private static String bearer(String subject, String scope) throws Exception {
    var token =
        new SignedJWT(
            new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(signingKey.getKeyID()).build(),
            new JWTClaimsSet.Builder()
                .issuer(issuerUrl())
                .audience("crud")
                .subject(subject)
                .issueTime(new Date())
                .expirationTime(Date.from(Instant.now().plusSeconds(600)))
                .claim("scope", scope)
                .claim("azp", "crud-client")
                .build());
    token.sign(new RSASSASigner(signingKey));
    return token.serialize();
  }

  @AfterAll
  static void stop() {
    if (http != null) http.close();
    if (context != null) context.close();
    if (issuer != null) issuer.stop(0);
    POSTGRES.stop();
  }

  private static HttpResponse<String> call(
      String method, String path, String token, String body, String... headers) throws Exception {
    var request =
        HttpRequest.newBuilder(URI.create(endpoint + path))
            .timeout(Duration.ofSeconds(10))
            .header("Content-Type", "application/json")
            .method(method, HttpRequest.BodyPublishers.ofString(body));
    if (!token.isEmpty()) request.header("Authorization", "Bearer " + token);
    if (headers.length > 0) request.headers(headers);
    return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
  }

  private static Map<String, Object> expect(HttpResponse<String> reply, int code) {
    assertEquals(code, reply.statusCode(), reply.body());
    return reply.body().isBlank() ? Map.of() : JsonReader.parseObject(reply.body());
  }

  private static Entry stored(String id) {
    try (var em = context.getBean(SessionFactory.class).createEntityManager()) {
      return em.find(Entry.class, id);
    }
  }

  @Test
  void signedCallersCannotAssignProtectedFieldsReadOrMutateAnotherOwnersRows() throws Exception {
    String alice = bearer("alice", "maintenance:write");
    String mallory = bearer("mallory", "maintenance:write");
    String noScope = bearer("mallory", "other");
    for (String resource : List.of("accounts", "limits")) {
      String path = "/v1/" + resource;
      var created = expect(call("POST", path, alice, "{\"name\":\"original\"}"), 201);
      String id = (String) created.get("id");
      assertEquals("alice", stored(id).owner);
      assertEquals(0, new BigDecimal("100").compareTo(stored(id).dailyAmount));
      expect(call("GET", path + "/" + id, "", ""), 401);
      expect(call("GET", path + "/" + id, noScope, ""), 403);
      expect(call("GET", path + "/" + id, mallory, ""), 404);
      expect(call("DELETE", path + "/" + id, mallory, "", "If-Match", "\"0\""), 404);
      expect(call("PUT", path + "/" + id, mallory, "{\"name\":\"stolen\",\"version\":0}"), 404);
      var attempted =
          call(
              "POST",
              path,
              mallory,
              "{\"name\":\"intruder\",\"id\":\""
                  + id
                  + "\",\"owner\":\"alice\",\"balance\":999999,\"status\":\"APPROVED\",\"dailyAmount\":0}");
      assertTrue(attempted.statusCode() == 400 || attempted.statusCode() == 201, attempted.body());
      if (attempted.statusCode() == 201) {
        String assigned = (String) JsonReader.parseObject(attempted.body()).get("id");
        assertThat(assigned).isNotEqualTo(id);
        assertEquals("mallory", stored(assigned).owner);
        assertEquals(0, stored(assigned).balance.signum());
        assertEquals("DRAFT", stored(assigned).status);
        assertEquals(0, new BigDecimal("100").compareTo(stored(assigned).dailyAmount));
      }
      var read = call("GET", path + "/" + id, alice, "");
      expect(read, 200);
      assertEquals(
          "same-origin", read.headers().firstValue("Cross-Origin-Resource-Policy").orElseThrow());
      assertEquals("alice", stored(id).owner);
      assertEquals("original", stored(id).name);
      assertEquals(0, new BigDecimal("100").compareTo(stored(id).dailyAmount));
      var list = call("GET", path, mallory, "");
      assertEquals(200, list.statusCode(), list.body());
      assertThat(list.body()).doesNotContain(id, "original");
      expect(call("PUT", path + "/" + id, alice, "{\"name\":\"updated\",\"version\":0}"), 200);
      expect(call("PUT", path + "/" + id, alice, "{\"name\":\"stale\",\"version\":0}"), 409);
      expect(call("PUT", path + "/" + id, alice, "{\"name\":\"missing-version\"}"), 400);
      assertEquals("updated", stored(id).name);
      assertEquals(1L, stored(id).version);
      for (String suffix :
          List.of("?page=abc", "?size=-1", "?sort=owner", "?sort=name,wrong", "?=&size=10")) {
        expect(call("GET", path + suffix, alice, ""), 400);
      }
      for (String body :
          List.of(
              "{", "[]", "{\"name\":null}", "{\"name\":\"\"}", "{\"name\":\"bad\\u0000value\"}"))
        expect(call("POST", path, alice, body), 400);
    }
  }

  @Test
  void deleteRequiresOneCurrentStrongVersionOverRealHttp() throws Exception {
    String alice = bearer("alice", "maintenance:write");
    for (String resource : List.of("accounts", "limits")) {
      String collection = "/v1/" + resource;
      var created = expect(call("POST", collection, alice, "{\"name\":\"delete-guard\"}"), 201);
      String path = collection + "/" + created.get("id");
      expect(call("DELETE", path, alice, ""), 400);
      for (String invalid : List.of("W/\"0\"", "*", "\"0\", \"1\"", "0"))
        expect(call("DELETE", path, alice, "", "If-Match", invalid), 400);
      expect(call("PUT", path, alice, "{\"name\":\"updated\",\"version\":0}"), 200);
      expect(call("DELETE", path, alice, "", "If-Match", "\"0\""), 412);
      var remaining = expect(call("GET", path, alice, ""), 200);
      assertEquals("updated", remaining.get("name"));
      assertEquals(1L, ((Number) remaining.get("version")).longValue());
      expect(call("DELETE", path, alice, "", "If-Match", "\"1\""), 204);
      expect(call("GET", path, alice, ""), 404);
    }
  }
}
