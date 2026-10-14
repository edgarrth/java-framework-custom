package pe.axiz.codegen.project;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PomEditorTest {

  private static final String POM =
      """
      <project>
        <parent>
          <groupId>pe.axiz</groupId>
          <artifactId>axiz-parent-service</artifactId>
          <version>1.0.0</version>
        </parent>
        <dependencyManagement>
          <dependencies>
            <dependency><groupId>x</groupId><artifactId>managed</artifactId></dependency>
          </dependencies>
        </dependencyManagement>
        <dependencies>
          <dependency>
            <groupId>a</groupId>
            <artifactId>b</artifactId>
          </dependency>
        </dependencies>
        <profiles>
          <profile>
            <dependencies>
              <dependency><groupId>p</groupId><artifactId>q</artifactId></dependency>
            </dependencies>
          </profile>
        </profiles>
      </project>
      """;

  @Test
  void addsToTheProjectDependenciesOnly() {
    String out = PomEditor.addDependency(POM, "pe.axiz", "axiz-cache-spring-boot-starter", null);
    int added = out.indexOf("axiz-cache-spring-boot-starter");
    assertTrue(
        added > out.indexOf("<artifactId>b</artifactId>") && added < out.indexOf("<profiles>"),
        out);
    assertEquals(
        out, PomEditor.addDependency(out, "pe.axiz", "axiz-cache-spring-boot-starter", null));
    assertTrue(PomEditor.addDependency(POM, "g", "t", "test").contains("<scope>test</scope>"));
  }

  @Test
  void readsAndUpdatesTheParentVersion() {
    assertEquals("1.0.0", PomEditor.parentVersion(POM));
    assertEquals("1.1.0", PomEditor.parentVersion(PomEditor.setParentVersion(POM, "1.1.0")));
  }
}
