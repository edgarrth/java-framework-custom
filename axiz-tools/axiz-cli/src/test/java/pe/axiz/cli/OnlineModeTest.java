package pe.axiz.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code axiz new --server}: the request the CLI sends to the initializr (JSON body, the user's
 * token) and how it unpacks the answer or reports a refusal.
 */
class OnlineModeTest {

  HttpServer server;
  final List<String> authorizations = new CopyOnWriteArrayList<>();
  final List<String> bodies = new CopyOnWriteArrayList<>();
  volatile int status = 200;
  volatile byte[] answer = new byte[0];

  @BeforeEach
  void start() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/v1/projects",
        exchange -> {
          String auth = exchange.getRequestHeaders().getFirst("Authorization");
          authorizations.add(auth == null ? "none" : auth);
          bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
          exchange.sendResponseHeaders(status, answer.length);
          try (var out = exchange.getResponseBody()) {
            out.write(answer);
          }
        });
    server.start();
  }

  @AfterEach
  void stop() {
    server.stop(0);
  }

  String url() {
    return "http://127.0.0.1:" + server.getAddress().getPort();
  }

  static byte[] zip(Map<String, String> entries) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    try (ZipOutputStream z = new ZipOutputStream(out)) {
      for (Map.Entry<String, String> e : entries.entrySet()) {
        z.putNextEntry(new ZipEntry(e.getKey()));
        z.write(e.getValue().getBytes(StandardCharsets.UTF_8));
        z.closeEntry();
      }
    }
    return out.toByteArray();
  }

  String[] newCommand(Path tmp) throws IOException {
    return new String[] {
      "new",
      "orders-business",
      "--archetype",
      "business",
      "--openapi",
      CliHarness.contract(tmp).toString(),
      "--server",
      url(),
      "--output",
      tmp.toString()
    };
  }

  @Test
  void theProjectComesFromTheInitializrWithTheUsersToken(@TempDir Path tmp) throws Exception {
    answer =
        zip(
            Map.of(
                "orders-business/", "",
                "orders-business/pom.xml", "<project/>",
                "orders-business/src/main/java/App.java", "class App {}"));
    CliHarness h = new CliHarness();
    h.env = Map.of("AXIZ_TOKEN", "user-token");

    assertEquals(0, h.run(newCommand(tmp)), h.output());

    assertEquals(List.of("Bearer user-token"), authorizations);
    String body = bodies.get(0);
    assertTrue(body.contains("\"name\":\"orders-business\""), body);
    assertTrue(body.contains("\"archetype\":\"business\""), body);
    assertTrue(body.contains("\"openApi\":\"openapi: 3.1"), body);
    assertEquals("<project/>", Files.readString(tmp.resolve("orders-business/pom.xml")));
    assertTrue(Files.exists(tmp.resolve("orders-business/src/main/java/App.java")));
    assertTrue(h.output().contains("Next steps:"));
  }

  @Test
  void withoutTokenNoAuthorizationIsSentAndARefusalIsReported(@TempDir Path tmp) throws Exception {
    status = 403;
    answer = "{\"title\":\"Forbidden\"}".getBytes(StandardCharsets.UTF_8);
    CliHarness h = new CliHarness();
    h.env = Map.of("AXIZ_TOKEN", " ");

    assertEquals(ExitCodes.PROBLEMS, h.run(newCommand(tmp)));

    assertEquals(List.of("none"), authorizations, "a blank token is not sent");
    assertTrue(
        h.output().contains("initializr answered 403: {\"title\":\"Forbidden\"}"), h.output());
  }

  @Test
  void longRefusalsAreCut() throws Exception {
    status = 500;
    answer = "e".repeat(5_000).getBytes(StandardCharsets.UTF_8);

    IOException e =
        assertThrows(
            IOException.class,
            () -> new InitializrClient(URI.create(url()), null).generate(Map.of("name", "x")));

    assertTrue(e.getMessage().endsWith("e".repeat(2000)));
    assertFalse(e.getMessage().contains("e".repeat(2001)));
  }

  @Test
  void anExistingFileIsKeptUnlessOverwriting(@TempDir Path tmp) throws Exception {
    Files.writeString(tmp.resolve("pom.xml"), "mine");
    byte[] archive = zip(Map.of("svc/pom.xml", "theirs"));

    ZipExtractor.extract(archive, tmp, false);
    assertEquals("mine", Files.readString(tmp.resolve("pom.xml")));

    ZipExtractor.extract(archive, tmp, true);
    assertEquals("theirs", Files.readString(tmp.resolve("pom.xml")));
  }

  @Test
  void entriesWithoutRootFolderAndEmptyNamesAreHandled(@TempDir Path tmp) throws Exception {
    assertEquals(2, ZipExtractor.extract(zip(Map.of("README.md", "hi", "svc/", "")), tmp, false));
    assertTrue(Files.exists(tmp.resolve("README.md")));
    assertNull(tmp.resolve("svc").toFile().list(), "directory entries are not written");
  }

  @Test
  void escapesEveryControlCharacter() {
    assertEquals("\"q\\\"b\\\\s\\nn\\rr\\tt\\u0001\"", JsonWriter.write("q\"b\\s\nn\rr\tt\u0001"));
    assertEquals(
        "[1.5,false,\"x\"]", JsonWriter.write(List.of(1.5, false, new StringBuilder("x"))));
  }
}
