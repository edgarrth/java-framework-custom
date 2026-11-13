package pe.axiz.spring.soap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
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

class SoapTest {

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
            ctx -> {
              IllegalStateException e =
                  Assertions.assertThrows(
                      IllegalStateException.class,
                      () ->
                          ctx.getBean(SoapClients.class).call("core", "a", "<A xmlns=\"urn:x\"/>"));
              assertThat(e).hasMessageContaining("password-mode is required");
            });
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
            ctx -> {
              IllegalStateException e =
                  Assertions.assertThrows(
                      IllegalStateException.class,
                      () ->
                          ctx.getBean(SoapClients.class).call("core", "a", "<A xmlns=\"urn:x\"/>"));
              assertThat(e).hasMessageContaining("axiz-secrets-spring-boot-starter");
            });
  }
}
