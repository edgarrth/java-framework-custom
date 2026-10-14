package pe.axiz.spring.soap;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.w3c.dom.Element;

class SoapTest {

  @Test
  void callsConfiguredClient() throws Exception {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/ws",
        exchange -> {
          byte[] body =
              ("<soapenv:Envelope"
                   + " xmlns:soapenv=\"http://schemas.xmlsoap.org/soap/envelope/\"><soapenv:Body><BalanceResponse"
                   + " xmlns=\"urn:bank\"><amount>10.00</amount></BalanceResponse></soapenv:Body></soapenv:Envelope>")
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
}
