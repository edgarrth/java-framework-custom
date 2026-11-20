package pe.axiz.spring.soap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsParameters;
import com.sun.net.httpserver.HttpsServer;
import io.micrometer.observation.ObservationRegistry;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.ssl.DefaultSslBundleRegistry;
import org.springframework.boot.ssl.SslBundle;
import org.springframework.boot.ssl.SslStoreBundle;
import pe.axiz.core.commons.net.TransportSecurityPolicy;
import pe.axiz.core.errors.ExternalServiceException;
import pe.axiz.core.soap.SoapVersion;

@Timeout(45)
class SoapTlsTest {
  @Test
  void perClientBundleSupportsMutualTlsAndNeverDisablesHostnameOrTrustChecks(@TempDir Path temp)
      throws Exception {
    Path store = temp.resolve("soap.p12");
    Path output = temp.resolve("keytool.log");
    var process =
        new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "keytool").toString(),
                "-genkeypair",
                "-alias",
                "soap",
                "-keyalg",
                "EC",
                "-groupname",
                "secp256r1",
                "-dname",
                "CN=localhost",
                "-ext",
                "SAN=dns:localhost",
                "-validity",
                "3",
                "-storetype",
                "PKCS12",
                "-keystore",
                store.toString(),
                "-storepass",
                "changeit")
            .redirectErrorStream(true)
            .redirectOutput(output.toFile())
            .start();
    try {
      assertTrue(process.waitFor(20, TimeUnit.SECONDS));
      assertEquals(0, process.exitValue(), Files.readString(output));
    } finally {
      if (process.isAlive()) process.destroyForcibly();
    }
    var keys = KeyStore.getInstance("PKCS12");
    try (var input = Files.newInputStream(store)) {
      keys.load(input, "changeit".toCharArray());
    }
    var km = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
    km.init(keys, "changeit".toCharArray());
    var trust = KeyStore.getInstance("PKCS12");
    trust.load(null, null);
    trust.setCertificateEntry("soap", keys.getCertificate("soap"));
    var tm = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
    tm.init(trust);
    var ssl = SSLContext.getInstance("TLSv1.3");
    ssl.init(km.getKeyManagers(), tm.getTrustManagers(), null);
    var server = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.setHttpsConfigurator(
        new HttpsConfigurator(ssl) {
          @Override
          public void configure(HttpsParameters parameters) {
            var options = ssl.getDefaultSSLParameters();
            options.setNeedClientAuth(true);
            parameters.setSSLParameters(options);
          }
        });
    try (var handlers = Executors.newVirtualThreadPerTaskExecutor()) {
      server.setExecutor(handlers);
      server.createContext(
          "/ws",
          exchange -> {
            try (exchange) {
              exchange.getRequestBody().readAllBytes();
              var response =
                  ("<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\"><s:Body><Ok/></s:Body></s:Envelope>")
                      .getBytes(StandardCharsets.UTF_8);
              exchange.sendResponseHeaders(200, response.length);
              exchange.getResponseBody().write(response);
            }
          });
      server.start();
      var bundles = new DefaultSslBundleRegistry();
      bundles.registerBundle("mutual", SslBundle.of(SslStoreBundle.of(keys, "changeit", trust)));
      bundles.registerBundle("trust-only", SslBundle.of(SslStoreBundle.of(null, null, trust)));
      int port = server.getAddress().getPort();
      var properties =
          new AxizSoapProperties(
              Map.of(
                  "trusted", client("localhost", port, "mutual"),
                  "wrong-host", client("127.0.0.1", port, "mutual"),
                  "no-client-cert", client("localhost", port, "trust-only")));
      try (var clients =
          new SoapClients(
              properties,
              null,
              ObservationRegistry.NOOP,
              Clock.systemUTC(),
              new TransportSecurityPolicy("test", false),
              bundles)) {
        assertEquals("Ok", clients.call("trusted", "urn:test", "<Request/>").getNodeName());
        assertThrows(
            ExternalServiceException.class,
            () -> clients.call("wrong-host", "urn:test", "<Request/>"));
        assertThrows(
            ExternalServiceException.class,
            () -> clients.call("no-client-cert", "urn:test", "<Request/>"));
      } finally {
        server.stop(0);
      }
    }
  }

  private static AxizSoapProperties.Client client(String host, int port, String bundle) {
    return new AxizSoapProperties.Client(
        URI.create("https://" + host + ":" + port + "/ws"),
        SoapVersion.SOAP_11,
        Duration.ofSeconds(2),
        Duration.ofSeconds(3),
        null,
        null,
        null,
        bundle);
  }
}
