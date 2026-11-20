package pe.axiz.spring.soap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.w3c.dom.Element;
import pe.axiz.core.secrets.SecretNotFoundException;
import pe.axiz.core.secrets.SecretResolver;
import pe.axiz.core.secrets.SecretValue;
import pe.axiz.core.soap.SoapVersion;
import pe.axiz.core.soap.WsSecurity;

class SoapTest {

  @Test
  void programmaticClientsKeepBindingDefaultsAndNamedOverrides() {
    URI endpoint = URI.create("https://bank.example/ws");
    var defaults = AxizSoapProperties.Client.builder(endpoint).build();
    assertThat(defaults)
        .isEqualTo(
            new AxizSoapProperties.Client(
                endpoint,
                SoapVersion.SOAP_11,
                Duration.ofSeconds(2),
                Duration.ofSeconds(10),
                null,
                null,
                null,
                null));
    var builder =
        AxizSoapProperties.Client.builder(endpoint)
            .version(SoapVersion.SOAP_12)
            .connectTimeout(Duration.ofSeconds(3))
            .timeout(Duration.ofSeconds(15))
            .username("service")
            .passwordRef("bank-credential")
            .passwordMode(WsSecurity.Mode.TEXT)
            .sslBundle("bank-mtls");
    var configured = builder.build();
    assertThat(configured)
        .isEqualTo(
            new AxizSoapProperties.Client(
                endpoint,
                SoapVersion.SOAP_12,
                Duration.ofSeconds(3),
                Duration.ofSeconds(15),
                "service",
                "bank-credential",
                WsSecurity.Mode.TEXT,
                "bank-mtls"));
    builder.sslBundle("another-bundle");
    assertThat(configured.sslBundle()).isEqualTo("bank-mtls");
  }

  @Test
  void unauthenticatedSoapEndpointsAlsoFailAtStartupWithoutTls() {
    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(AxizSoapAutoConfiguration.class))
        .withPropertyValues(
            "axiz.config.environment=prod",
            "axiz.transport.allow-plain-http=true",
            "axiz.soap.clients.core.endpoint=http://localhost:8080/ws")
        .run(context -> assertThat(context).hasFailed());
  }

  @Test
  void callsConfiguredClient() throws Exception {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/ws",
        exchange -> {
          byte[] body =
              ("<soapenv:Envelope"
                      + " xmlns:soapenv=\"http://schemas.xmlsoap.org/soap/envelope/\">"
                      + "<soapenv:Body><BalanceResponse xmlns=\"urn:bank\">"
                      + "<amount>10.00</amount></BalanceResponse>"
                      + "</soapenv:Body></soapenv:Envelope>")
                  .getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "text/xml; charset=utf-8");
          exchange.sendResponseHeaders(200, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });
    server.start();
    try {
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(AxizSoapAutoConfiguration.class))
          .withPropertyValues(
              "axiz.config.environment=test",
              "axiz.transport.allow-plain-http=true",
              "axiz.soap.clients.core-bank.endpoint=http://127.0.0.1:"
                  + server.getAddress().getPort()
                  + "/ws")
          .run(
              ctx -> {
                Element response =
                    ctx.getBean(SoapClients.class)
                        .call("core-bank", "urn:bank:balance", "<Balance xmlns=\"urn:bank\"/>");
                assertEquals("BalanceResponse", response.getLocalName());
              });
    } finally {
      server.stop(0);
    }
  }

  private static HttpServer server(AtomicReference<String> request) throws Exception {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/ws",
        exchange -> {
          request.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
          byte[] body =
              ("<soapenv:Envelope xmlns:soapenv=\"http://schemas.xmlsoap.org/soap/envelope/\">"
                      + "<soapenv:Body><Ok xmlns=\"urn:bank\"/></soapenv:Body></soapenv:Envelope>")
                  .getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "text/xml; charset=utf-8");
          exchange.sendResponseHeaders(200, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });
    server.start();
    return server;
  }

  private static final SecretResolver SECRETS =
      name -> "core-pw".equals(name) ? Optional.of(SecretValue.of("s3cret")) : Optional.empty();

  private ApplicationContextRunner withCredentials(String endpoint, String... extra) {
    return new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(AxizSoapAutoConfiguration.class))
        .withBean(SecretResolver.class, () -> SECRETS)
        .withPropertyValues(
            "axiz.soap.clients.core.endpoint=" + endpoint,
            "axiz.soap.clients.core.username=svc",
            "axiz.soap.clients.core.password-ref=core-pw")
        .withPropertyValues(extra);
  }

  @Test
  void credentialsNeedAnExplicitPasswordModeAndTls() throws Exception {
    withCredentials("https://core.example/ws")
        .run(
            ctx ->
                assertThat(ctx)
                    .hasFailed()
                    .getFailure()
                    .hasRootCauseMessage(
                        "axiz.soap.clients.core.password-mode is required with a username: "
                            + "TEXT (over TLS) or LEGACY_SHA1_DIGEST (only when the counterparty demands it)"));

    withCredentials(
            "http://core.example/ws",
            "axiz.soap.clients.core.password-mode=TEXT",
            "axiz.config.environment=prod",
            "axiz.transport.allow-plain-http=false")
        .run(ctx -> assertThat(ctx).hasFailed());
  }

  @Test
  void aLocalStandInMayReceiveTheLegacyDigestWhenExplicitlyAllowed() throws Exception {
    AtomicReference<String> request = new AtomicReference<>();
    HttpServer server = server(request);
    try {
      withCredentials(
              "http://127.0.0.1:" + server.getAddress().getPort() + "/ws",
              "axiz.soap.clients.core.password-mode=LEGACY_SHA1_DIGEST",
              "axiz.config.environment=test",
              "axiz.transport.allow-plain-http=true")
          .run(
              ctx -> {
                ctx.getBean(SoapClients.class).call("core", "urn:a", "<A xmlns=\"urn:x\"/>");
                assertTrue(request.get().contains("#PasswordDigest"), request.get());
                assertTrue(
                    !request.get().contains("s3cret"), "the password never travels in clear");
              });
      withCredentials(
              "http://127.0.0.1:" + server.getAddress().getPort() + "/ws",
              "axiz.soap.clients.core.password-mode=TEXT",
              "axiz.config.environment=test",
              "axiz.transport.allow-plain-http=true")
          .run(
              ctx -> {
                ctx.getBean(SoapClients.class).call("core", "urn:a", "<A xmlns=\"urn:x\"/>");
                assertTrue(request.get().contains("#PasswordText"), request.get());
              });
    } finally {
      server.stop(0);
    }
  }

  @Test
  void eachCallReadsRotatedCredentialsAndClosesResolvedSecrets() throws Exception {
    var request = new AtomicReference<String>();
    var issued = new ArrayList<SecretValue>();
    var current = new AtomicReference<>("first-password");
    var server = server(request);
    try {
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(AxizSoapAutoConfiguration.class))
          .withBean(
              SecretResolver.class,
              () ->
                  name -> {
                    var value = SecretValue.of(current.get());
                    issued.add(value);
                    return Optional.of(value);
                  })
          .withPropertyValues(
              "axiz.config.environment=test",
              "axiz.transport.allow-plain-http=true",
              "axiz.soap.clients.core.endpoint=http://127.0.0.1:"
                  + server.getAddress().getPort()
                  + "/ws",
              "axiz.soap.clients.core.username=service",
              "axiz.soap.clients.core.password-ref=soap-password",
              "axiz.soap.clients.core.password-mode=TEXT")
          .run(
              context -> {
                var clients = context.getBean(SoapClients.class);
                clients.call("core", "urn:balance", "<Balance/>");
                assertTrue(request.get().contains("first-password"));
                current.set("rotated-password");
                clients.call("core", "urn:balance", "<Balance/>");
                assertTrue(request.get().contains("rotated-password"));
                assertEquals(2, issued.size());
                issued.forEach(
                    secret -> Assertions.assertThrows(IllegalStateException.class, secret::reveal));
              });
    } finally {
      server.stop(0);
    }
  }

  @Test
  void aConfiguredSslBundleMustExist() {
    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(AxizSoapAutoConfiguration.class))
        .withPropertyValues(
            "axiz.soap.clients.core.endpoint=https://localhost/ws",
            "axiz.soap.clients.core.ssl-bundle=bank")
        .run(
            context ->
                assertThat(
                        Assertions.assertThrows(
                            IllegalStateException.class,
                            () ->
                                context
                                    .getBean(SoapClients.class)
                                    .call("core", "urn:balance", "<Balance/>")))
                    .hasMessageContaining("SSL bundles"));
  }

  @Test
  void misconfiguredClientsFailOnFirstUseWithTheSettingToFix() {
    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(AxizSoapAutoConfiguration.class))
        .run(
            ctx -> {
              IllegalArgumentException e =
                  Assertions.assertThrows(
                      IllegalArgumentException.class,
                      () ->
                          ctx.getBean(SoapClients.class).call("nope", "a", "<A xmlns=\"urn:x\"/>"));
              assertThat(e).hasMessageContaining("axiz.soap.clients");
            });
    withCredentials(
            "https://core.example/ws",
            "axiz.soap.clients.core.password-mode=TEXT",
            "axiz.soap.clients.core.password-ref=missing-pw")
        .run(
            ctx -> {
              SecretNotFoundException e =
                  Assertions.assertThrows(
                      SecretNotFoundException.class,
                      () ->
                          ctx.getBean(SoapClients.class).call("core", "a", "<A xmlns=\"urn:x\"/>"));
              assertThat(e).hasMessageContaining("missing-pw").hasMessageNotContaining("s3cret");
            });
    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(AxizSoapAutoConfiguration.class))
        .withPropertyValues(
            "axiz.soap.clients.core.endpoint=https://core.example/ws",
            "axiz.soap.clients.core.username=svc",
            "axiz.soap.clients.core.password-ref=core-pw",
            "axiz.soap.clients.core.password-mode=TEXT")
        .run(
            ctx ->
                assertThat(ctx)
                    .hasFailed()
                    .getFailure()
                    .hasRootCauseMessage(
                        "axiz.soap.clients.core.password-ref needs a SecretResolver: "
                            + "add axiz-secrets-spring-boot-starter"));
  }
}
