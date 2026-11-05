package pe.axiz.rewrite;

import static org.openrewrite.yaml.Assertions.yaml;

import org.junit.jupiter.api.Test;
import org.openrewrite.test.RewriteTest;

class RateLimitPolicyMigrationTest implements RewriteTest {
  @Test
  void removesOnlyTheGlobalPolicyAndPreservesEachExplicitRulePolicy() {
    rewriteRun(
        spec ->
            spec.recipeFromResource("/META-INF/rewrite/axiz.yml", "pe.axiz.rewrite.MigrateToAxiz2"),
        yaml(
            """
        axiz:
          security:
            rate-limit:
              enabled: true
              fail-open: true
              rules:
                - path: /v1/payments
                  fail-open: false
                - path: /v1/catalog
                  fail-open: true
        """,
            """
        axiz:
          security:
            rate-limit:
              enabled: true
              rules:
                - path: /v1/payments
                  fail-open: false
                - path: /v1/catalog
                  fail-open: true
        """));
  }
}
