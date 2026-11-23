package pe.axiz.demo.providers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestReporter;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.web.reactive.function.client.WebClient;
import pe.axiz.core.errors.ExternalServiceException;
import pe.axiz.core.secrets.SecretResolver;
import pe.axiz.core.secrets.SecretValue;
import pe.axiz.spring.httpclient.AxizHttpClientAutoConfiguration;
import pe.axiz.spring.httpclient.AxizHttpClients;
import pe.axiz.spring.httpclient.AxizWebClients;
import pe.axiz.spring.httpclient.HttpClientRegistry;
import pe.axiz.spring.resilience.AxizResilienceAutoConfiguration;
import pe.axiz.spring.soap.AxizSoapAutoConfiguration;
import pe.axiz.spring.soap.SoapClients;

/**
 * Los pares de loopback ejercitan transportes HTTP y XML reales. Los pares de producción exigen
 * TLS.
 */
class OutboundRecipesTest {
  @Test
  void httpRetriesSafeOperationsButNeverReplaysAnUnkeyedPayment(TestReporter evidence)
      throws Exception {
    var gets = new AtomicInteger();
    var posts = new AtomicInteger();
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/payments",
        exchange -> {
          boolean get = exchange.getRequestMethod().equals("GET");
          int attempt = (get ? gets : posts).incrementAndGet();
          byte[] body = "{\"state\":\"ready\"}".getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(get && attempt > 1 ? 200 : 503, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });
    server.start();
    try {
      new ApplicationContextRunner()
          .withConfiguration(
              AutoConfigurations.of(
                  AxizHttpClientAutoConfiguration.class, AxizResilienceAutoConfiguration.class))
          .withPropertyValues(
              "axiz.config.environment=test",
              "axiz.transport.allow-plain-http=true",
              "axiz.http-client.clients.bank.base-url=http://127.0.0.1:"
                  + server.getAddress().getPort(),
              "axiz.http-client.clients.bank.max-attempts=2")
          .run(
              ctx -> {
                var bank = ctx.getBean(AxizHttpClients.class).restClient("bank");
                assertThat(bank.get().uri("/payments").retrieve().body(String.class))
                    .contains("ready");
                assertThat(gets).hasValue(2);
                assertThatThrownBy(() -> bank.post().uri("/payments").retrieve().toBodilessEntity())
                    .isInstanceOf(ExternalServiceException.class);
                assertThat(posts).hasValue(1);
                evidence.publishEntry("axiz.capability.http-client", "servlet");
                evidence.publishEntry("axiz.capability.resilience", "servlet");
                gets.set(0);
                var builder = WebClient.builder();
                var settings = ctx.getBean(HttpClientRegistry.class).find("bank").orElseThrow();
                ctx.getBean(AxizWebClients.class).configure(settings, builder);
                assertThat(
                        builder
                            .build()
                            .get()
                            .uri("/payments")
                            .retrieve()
                            .bodyToMono(String.class)
                            .block(Duration.ofSeconds(10)))
                    .contains("ready");
                assertThat(gets).hasValue(2);
                evidence.publishEntry("axiz.capability.http-client", "reactive");
                evidence.publishEntry("axiz.capability.resilience", "reactive");
              });
    } finally {
      server.stop(0);
    }
  }

  @Test
  void soapReadsRotatedSecretsOnEveryInvocation(TestReporter evidence) throws Exception {
    var password = new AtomicReference<>("synthetic-version-one");
    var received = new AtomicReference<String>();
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/core",
        exchange -> {
          received.set(
              new String(exchange.getRequestBody().readNBytes(16384), StandardCharsets.UTF_8));
          byte[] body =
              ("<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\">"
                      + "<s:Body><Balance xmlns=\"urn:recipe\">10.00</Balance></s:Body></s:Envelope>")
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
          .withBean(SecretResolver.class, () -> name -> Optional.of(SecretValue.of(password.get())))
          .withPropertyValues(
              "axiz.config.environment=test",
              "axiz.transport.allow-plain-http=true",
              "axiz.soap.clients.bank.endpoint=http://127.0.0.1:"
                  + server.getAddress().getPort()
                  + "/core",
              "axiz.soap.clients.bank.username=recipe-client",
              "axiz.soap.clients.bank.password-ref=bank-secret",
              "axiz.soap.clients.bank.password-mode=TEXT")
          .run(
              ctx -> {
                var bank = ctx.getBean(SoapClients.class);
                assertThat(
                        bank.call(
                                "bank",
                                "urn:recipe:balance",
                                "<BalanceRequest xmlns=\"urn:recipe\"/>")
                            .getTextContent())
                    .isEqualTo("10.00");
                assertThat(received.get()).contains("synthetic-version-one");
                password.set("synthetic-version-two");
                bank.call("bank", "urn:recipe:balance", "<BalanceRequest xmlns=\"urn:recipe\"/>");
                assertThat(received.get())
                    .contains("synthetic-version-two")
                    .doesNotContain("synthetic-version-one");
              });
    } finally {
      server.stop(0);
    }

    evidence.publishEntry("axiz.capability.soap", "servlet");
  }
}
