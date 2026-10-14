package pe.axiz.spring.test;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;

/**
 * WireMock stub for a logical HTTP client: starts on a random port and points {@code
 * axiz.http-client.clients.<name>.base-url} at it.
 */
public final class HttpStubs {

  private HttpStubs() {}

  public static WireMockServer start(String clientName, DynamicPropertyRegistry registry) {
    WireMockServer server = new WireMockServer(WireMockConfiguration.options().dynamicPort());
    server.start();
    registry.add("axiz.http-client.clients." + clientName + ".base-url", server::baseUrl);
    return server;
  }
}
