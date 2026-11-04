package pe.axiz.codegen.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class YamlPropertiesTest {

  @Test
  void nestsFlatKeysWithLists() {
    Map<String, Object> flat = new LinkedHashMap<>();
    flat.put("spring.application.name", "svc");
    flat.put("axiz.security.issuers[0].issuer", "https://idp");
    flat.put("axiz.security.issuers[0].audiences[0]", "svc");
    flat.put("axiz.web.api-version.supported", List.of("1"));
    String yaml = YamlProperties.dump(YamlProperties.nest(flat), 0);
    assertTrue(yaml.contains("spring:\n  application:\n    name: svc\n"), yaml);
    assertTrue(
        yaml.contains(
            "  security:\n"
                + "    issuers:\n"
                + "    - issuer: https://idp\n"
                + "      audiences:\n"
                + "      - svc\n"),
        yaml);
  }

  @Test
  void flattenIsTheInverseOfNest() {
    Map<String, Object> flat = new LinkedHashMap<>();
    flat.put("a.b", 1);
    flat.put("a.c[0].d", "x");
    flat.put("e", List.of("1", "2"));
    assertEquals(flat, YamlProperties.flatten(YamlProperties.nest(flat)));
  }

  @Test
  @SuppressWarnings("unchecked")
  void mergeKeepsExistingEntriesAndOverridesGivenOnes() {
    String merged = YamlProperties.merge("a:\n  b: 1\n  c: 2\n", Map.of("a.c", 3, "d.e", "x"));
    assertEquals(
        Map.of("a.b", 1, "a.c", 3, "d.e", "x"),
        YamlProperties.flatten((Map<String, ?>) new org.yaml.snakeyaml.Yaml().load(merged)));
  }

  @Test
  void indentsForEmbedding() {
    assertEquals("    a: 1\n", YamlProperties.dump(Map.of("a", 1), 4));
    assertEquals("{}\n", YamlProperties.dump(Map.of(), 0));
  }

  @Test
  void mergeCanDropPlaceholderEntries() {
    String yaml =
        "axiz:\n  http-client:\n    clients:\n      example:\n        base-url: http://x\n"
            + "      other:\n        base-url: http://o\n";
    String merged =
        YamlProperties.merge(
            yaml,
            Map.of("axiz.http-client.clients.core.base-url", "http://core"),
            List.of("axiz.http-client.clients.example"));
    assertTrue(!merged.contains("example"), merged);
    assertTrue(merged.contains("other:") && merged.contains("core:"), merged);
  }

  @Test
  void mergingSpringKeysPreservesLiteralMetadataKeysAndFlatConfiguration() {
    String source =
        """
        serviceAccount:
          annotations:
            eks.amazonaws.com/role-arn: arn:aws:iam::111111111111:role/old
        config:
          axiz.security.issuers:
            - issuer: https://idp
              audiences: [old]
          axiz.http-client.clients:
            bank:
              headers:
                X.Correlation: stable
        """;
    String merged =
        YamlProperties.merge(
            source,
            Map.of(
                "config.axiz.security.issuers[0].audiences", List.of("old", "new"),
                "config.axiz.http-client.clients.bank.base-url", "https://bank",
                "migration.serviceAliases", List.of("old")));
    Map<?, ?> document = new org.yaml.snakeyaml.Yaml().load(merged);
    assertEquals(
        Map.of(
            "annotations",
            Map.of("eks.amazonaws.com/role-arn", "arn:aws:iam::111111111111:role/old")),
        document.get("serviceAccount"));
    Map<?, ?> config = (Map<?, ?>) document.get("config");
    assertTrue(config.containsKey("axiz.security.issuers"), merged);
    Map<?, ?> issuer = (Map<?, ?>) ((List<?>) config.get("axiz.security.issuers")).getFirst();
    assertEquals(List.of("old", "new"), issuer.get("audiences"));
    Map<?, ?> bank = (Map<?, ?>) ((Map<?, ?>) config.get("axiz.http-client.clients")).get("bank");
    assertEquals(Map.of("X.Correlation", "stable"), bank.get("headers"));
    assertEquals("https://bank", bank.get("base-url"));
  }
}
